package app.fractal.ink

import kotlin.math.*
import java.util.UUID
import java.time.Instant

private fun distance(a: InkPoint, b: InkPoint): Float = hypot(a.x - b.x, a.y - b.y)
private fun lerp(a: InkPoint, b: InkPoint, f: Float) = InkPoint(
    a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f,
    a.pressure + (b.pressure - a.pressure) * f,
    (a.tMillis + (b.tMillis - a.tMillis) * f).toLong()
)
private fun segmentDistance(p: InkPoint, a: InkPoint, b: InkPoint): Float {
    val dx = b.x - a.x; val dy = b.y - a.y
    val t = if (dx * dx + dy * dy < 1e-10f) 0f else ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)
    return distance(p, lerp(a, b, t.coerceIn(0f, 1f)))
}
private fun nearPath(p: InkPoint, path: List<InkPoint>, radius: Float): Boolean =
    path.zipWithNext().any { (a, b) -> segmentDistance(p, a, b) <= radius } ||
        (path.size == 1 && distance(p, path[0]) <= radius)

private fun simplify(points: List<InkPoint>, epsilon: Float): List<InkPoint> {
    if (points.size < 3) return points
    val a = points.first(); val b = points.last()
    var maxDistance = 0f; var index = -1
    for (i in 1 until points.lastIndex) {
        val distance = segmentDistance(points[i], a, b)
        if (distance > maxDistance) { maxDistance = distance; index = i }
    }
    if (index < 0 || maxDistance <= epsilon) return listOf(a,b)
    return simplify(points.subList(0,index+1),epsilon).dropLast(1) + simplify(points.subList(index,points.size),epsilon)
}

/** Return pieces outside the eraser sweep. Cut points are interpolated in vector space. */
fun splitStroke(stroke: InkStroke, eraserPath: List<InkPoint>, radius: Float): List<InkStroke> {
    if (stroke.points.isEmpty() || eraserPath.isEmpty()) return listOf(stroke)
    val samples = buildList {
        stroke.points.zipWithNext().forEach { (a, b) ->
            val steps = max(1, ceil(distance(a, b) / (radius.coerceAtLeast(0.0001f) * 0.25f)).toInt().coerceAtMost(256))
            for (i in 0 until steps) add(lerp(a, b, i.toFloat() / steps))
        }
        add(stroke.points.last())
    }
    val chunks = mutableListOf<MutableList<InkPoint>>()
    var current = mutableListOf<InkPoint>()
    for (point in samples) {
        if (nearPath(point, eraserPath, radius + stroke.width / 2)) {
            if (current.size >= 2) chunks.add(current)
            current = mutableListOf()
        } else current.add(point)
    }
    if (current.size >= 2) chunks.add(current)
    if (chunks.size == 1 && chunks[0].size == samples.size) return listOf(stroke)
    return chunks.map { stroke.copy(id = UUID.randomUUID().toString(), points = it) }
}

fun eraseStrokes(strokes: List<InkStroke>, path: List<InkPoint>, radius: Float, mode: EraserMode): List<InkStroke> =
    strokes.flatMap { stroke ->
        if (stroke.deleted) listOf(stroke)
        else if (mode == EraserMode.Partial) {
            val pieces = splitStroke(stroke,path,radius)
            if (pieces.size == 1 && pieces[0].id == stroke.id) listOf(stroke)
            else listOf(stroke.copy(deleted=true,rev=stroke.rev+1,updatedAt=Instant.now().toString())) + pieces.map { it.copy(rev=0,updatedAt=Instant.now().toString()) }
        }
        else if (stroke.points.any { nearPath(it, path, radius + stroke.width / 2) } ||
            stroke.points.zipWithNext().any { (a, b) ->
                path.any { segmentDistance(it, a, b) <= radius + stroke.width / 2 }
            }) listOf(stroke.copy(deleted=true,rev=stroke.rev+1,updatedAt=Instant.now().toString())) else listOf(stroke)
    }

data class RecognizedShape(val type: String, val points: List<InkPoint>, val confidence: Float)

/** Bounded resampling makes fitting independent of input event frequency. */
private fun shapeSamples(points: List<InkPoint>): List<InkPoint> {
    val length = points.zipWithNext().sumOf { (a,b) -> distance(a,b).toDouble() }.toFloat()
    if (length < .0001f) return points.take(1)
    val step = length / 192
    val result = mutableListOf(points.first())
    var traversed = 0f; var target = step
    for (i in 1 until points.size) {
        val a = points[i-1]; val b = points[i]; val d = distance(a,b)
        while (target <= traversed+d && result.size < 193) {
            result.add(lerp(a,b,((target-traversed)/d.coerceAtLeast(.000001f)).coerceIn(0f,1f)))
            target += step
        }
        traversed += d
    }
    result.add(points.last())
    return result
}

/** Fits a bounded, arc-length sampled path. Smooth curves need a full ellipse fit;
 * polygons must have sustained straight edges and distinct corners. */
fun recognizeShape(points: List<InkPoint>): RecognizedShape? {
    if (points.size < 4) return null
    val q = shapeSamples(points)
    val diagonal = hypot(q.maxOf { it.x }-q.minOf { it.x }, q.maxOf { it.y }-q.minOf { it.y })
    if (diagonal < .015f) return null
    val length = q.zipWithNext().sumOf { (a,b) -> distance(a,b).toDouble() }.toFloat()
    val closed = distance(q.first(),q.last()) < diagonal*.20f && length > diagonal*1.8f
    fun result(type: String, vertices: List<InkPoint>, error: Float) = RecognizedShape(type,vertices,1-error)
    run {
        val lineError = q.maxOf { segmentDistance(it,q.first(),q.last()) }/diagonal
        if (!closed && lineError < .045f && length < distance(q.first(),q.last())*1.12f) {
            var a=q.first(); var b=q.last()
            if (abs(a.x-b.x)<diagonal*.065f) { val x=(a.x+b.x)/2; a=a.copy(x=x); b=b.copy(x=x) }
            if (abs(a.y-b.y)<diagonal*.065f) { val y=(a.y+b.y)/2; a=a.copy(y=y); b=b.copy(y=y) }
            return result("line",listOf(a,b),lineError)
        }
        val vertices = simplify(q,diagonal*.025f)
        // Arrow strokes revisit a tip after each wing. Require a long shaft and
        // wings pointing backwards on opposite sides of that shaft.
        val shaft = vertices.zipWithNext().maxByOrNull { (a,b) -> distance(a,b) }
        if (vertices.size in 4..10 && length < diagonal*3.2f && shaft != null && distance(shaft.first,shaft.second)>diagonal*.55f) {
            val (a,b)=shaft; val dx=b.x-a.x; val dy=b.y-a.y; val d=hypot(dx,dy)
            fun hasHead(tip: InkPoint, direction: Float): Boolean {
                val wings=vertices.filter { p ->
                    val along=((p.x-tip.x)*dx+(p.y-tip.y)*dy)/d*direction
                    val across=abs((p.x-tip.x)*dy-(p.y-tip.y)*dx)/d
                    along in (-d*.48f)..(-d*.06f) && across in (d*.035f)..(d*.35f)
                }
                return wings.any { ((it.x-tip.x)*dy-(it.y-tip.y)*dx)>0 } &&
                    wings.any { ((it.x-tip.x)*dy-(it.y-tip.y)*dx)<0 } &&
                    vertices.count { distance(it,tip)<diagonal*.09f }>=2
            }
            val endHead=hasHead(b,1f); val startHead=hasHead(a,-1f)
            if (endHead || startHead) return explicitShape(if(endHead)a else b,if(endHead)b else a,
                if(endHead && startHead) ShapeMode.DoubleArrow else ShapeMode.Arrow).copy(confidence=.9f)
        }
        if (!closed && !selfIntersects(vertices, false) && vertices.size in 3..9 && length < diagonal*3.5f && polygonEdgesAreStraight(q,vertices,diagonal,false))
            return result("polyline",vertices,.05f)
        if (!closed) return null
    }
    // Rotate the closed seam to an extreme point so a mid-edge pen-down does
    // not introduce a spurious corner. The final collinear seam is removed.
    val seam=q.indices.minBy { q[it].x+q[it].y*.173f }
    val ring=q.drop(seam)+q.take(seam)
    var vertices=simplify(ring+ring.first(),diagonal*.035f).dropLast(1).toMutableList()
    var changed=true
    while(changed && vertices.size>3) {
        changed=false
        for(i in vertices.indices) {
            if(segmentDistance(vertices[i],vertices[(i+vertices.size-1)%vertices.size],vertices[(i+1)%vertices.size])<diagonal*.035f) {
                vertices.removeAt(i); changed=true; break
            }
        }
    }
    if(vertices.size in 3..12 && polygonEdgesAreStraight(q,vertices,diagonal,true)) {
        val edges=vertices.indices.map { i -> distance(vertices[i],vertices[(i+1)%vertices.size]) }
        if(edges.min()<diagonal*.10f) return null
        val cross=vertices.indices.map { i ->
            val a=vertices[i];val b=vertices[(i+1)%vertices.size];val c=vertices[(i+2)%vertices.size]
            (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x)
        }
        var type=when(vertices.size) {3->"triangle";4->"quadrilateral";5->"pentagon";6->"hexagon";else->"polygon"}
        if(vertices.size==10 && cross.zipWithNext().all { (a,b)->a*b<0 }) type="star"
        else if(selfIntersects(vertices,true)) return null
        if(vertices.size==3) {
            val equal=edges.max()/edges.min()<1.15f
            if(equal) {
                val cx=vertices.map{it.x}.average().toFloat();val cy=vertices.map{it.y}.average().toFloat()
                val radius=vertices.map{hypot(it.x-cx,it.y-cy)}.average().toFloat()
                val start=atan2(vertices[0].y-cy,vertices[0].x-cx)
                val direction=if(cross[0]>0) 1 else -1
                vertices=(0..2).map { i -> val a=start+direction*2*PI.toFloat()*i/3
                    vertices[i].copy(x=cx+cos(a)*radius,y=cy+sin(a)*radius)
                }.toMutableList()
            } else {
                for(i in 0..2) {
                    val b=vertices[i];val a=vertices[(i+1)%3];val c=vertices[(i+2)%3]
                    val dx=a.x-b.x;val dy=a.y-b.y;val len=hypot(dx,dy)
                    val dot=((c.x-b.x)*dx+(c.y-b.y)*dy)/len
                    if(abs(dot)/distance(b,c)<.12f) {
                        vertices[(i+2)%3]=c.copy(x=c.x-dot*dx/len,y=c.y-dot*dy/len)
                        break
                    }
                }
            }
        }
        if(vertices.size==4) {
            val right=vertices.indices.all { i ->
                val a=vertices[i];val b=vertices[(i+1)%4];val c=vertices[(i+2)%4]
                abs((a.x-b.x)*(c.x-b.x)+(a.y-b.y)*(c.y-b.y))/(edges[i]*edges[(i+1)%4])<.20f
            }
            val equal=edges.max()/edges.min()<1.18f
            if(right) {
                type="rectangle"
                // Orthogonal edge frame preserves rotation, regularizes near squares.
                val cx=vertices.map{it.x}.average().toFloat();val cy=vertices.map{it.y}.average().toFloat()
                val angle=atan2(vertices[1].y-vertices[0].y,vertices[1].x-vertices[0].x)
                var w=(edges[0]+edges[2])/2;var h=(edges[1]+edges[3])/2
                if(equal) { val side=(w+h)/2;w=side;h=side }
                val ux=cos(angle);val uy=sin(angle)
                vertices= listOf(-1f to -1f,1f to -1f,1f to 1f,-1f to 1f).map { (x,y)->
                    q.first().copy(x=cx+x*w/2*ux-y*h/2*uy,y=cy+x*w/2*uy+y*h/2*ux)
                }.toMutableList()
            } else if(equal) type="diamond"
        }
        return result(type,vertices+vertices.first(),.04f)
    }
    // PCA supplies the ellipse orientation. Extents in that frame avoid the
    // perimeter sampling bias of using covariance eigenvalues as radii.
    val cx=q.map{it.x}.average().toFloat();val cy=q.map{it.y}.average().toFloat()
    var xx=0f;var yy=0f;var xy=0f
    for(p in q) {val x=p.x-cx;val y=p.y-cy;xx+=x*x;yy+=y*y;xy+=x*y}
    val angle=.5f*atan2(2*xy,xx-yy);val ux=cos(angle);val uy=sin(angle)
    val local=q.map { (it.x-cx)*ux+(it.y-cy)*uy to -(it.x-cx)*uy+(it.y-cy)*ux }
    val mx=(local.maxOf{it.first}+local.minOf{it.first})/2;val my=(local.maxOf{it.second}+local.minOf{it.second})/2
    var rx=(local.maxOf{it.first}-local.minOf{it.first})/2;var ry=(local.maxOf{it.second}-local.minOf{it.second})/2
    if(min(rx,ry)<diagonal*.10f) return null
    val error=local.map { abs(hypot((it.first-mx)/rx,(it.second-my)/ry)-1f) }.average().toFloat()
    if(error>.065f || length>2*PI*max(rx,ry)*1.15) return null
    if(max(rx,ry)/min(rx,ry)<1.12f) {val r=(rx+ry)/2;rx=r;ry=r}
    return result("ellipse",(0..64).map { i ->
        val t=2*PI*i/64;val x=mx+cos(t).toFloat()*rx;val y=my+sin(t).toFloat()*ry
        q.first().copy(x=cx+x*ux-y*uy,y=cy+x*uy+y*ux)
    },error)
}

private fun selfIntersects(v: List<InkPoint>, closed: Boolean): Boolean {
    val n=if(closed) v.size else v.size-1
    fun cross(a: InkPoint,b: InkPoint,c: InkPoint)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    for(i in 0 until n) for(j in i+2 until n) {
        if(closed && i==0 && j==n-1) continue
        val a=v[i];val b=v[(i+1)%v.size];val c=v[j];val d=v[(j+1)%v.size]
        if(cross(a,b,c)*cross(a,b,d)<0 && cross(c,d,a)*cross(c,d,b)<0) return true
    }
    return false
}

private fun polygonEdgesAreStraight(samples: List<InkPoint>, vertices: List<InkPoint>, diagonal: Float, closed: Boolean): Boolean {
    val count=if(closed) vertices.size else vertices.size-1
    if(count<2) return false
    val errors=samples.map { p -> (0 until count).minOf { i -> segmentDistance(p,vertices[i],vertices[(i+1)%vertices.size]) }/diagonal }
    if(errors.average()>.012 || errors.max()>.045f) return false
    // Curves simplified into many small chords are not intentional corners.
    return (if(closed) vertices.indices else 1 until vertices.lastIndex).all { i ->
        val a=vertices[(i+vertices.size-1)%vertices.size];val b=vertices[i];val c=vertices[(i+1)%vertices.size]
        val cosine=((a.x-b.x)*(c.x-b.x)+(a.y-b.y)*(c.y-b.y))/(distance(a,b)*distance(b,c)).coerceAtLeast(.000001f)
        cosine>-.86f
    }
}

fun explicitShape(start: InkPoint, end: InkPoint, mode: ShapeMode): RecognizedShape {
    val left = min(start.x, end.x); val right = max(start.x, end.x)
    val top = min(start.y, end.y); val bottom = max(start.y, end.y)
    fun point(x: Float, y: Float) = start.copy(x = x, y = y)
    val points = when (mode) {
        ShapeMode.Auto, ShapeMode.Line -> listOf(start, end)
        ShapeMode.Arrow, ShapeMode.DoubleArrow -> {
            val dx = end.x-start.x; val dy = end.y-start.y
            val length = hypot(dx,dy).coerceAtLeast(.0001f); val back = min(.12f,length*.25f)
            val head = listOf(start,end,point(end.x-dx/length*back-dy/length*back*.5f,end.y-dy/length*back+dx/length*back*.5f),end,
                point(end.x-dx/length*back+dy/length*back*.5f,end.y-dy/length*back-dx/length*back*.5f))
            if (mode == ShapeMode.DoubleArrow) head + listOf(start, point(start.x+dx/length*back-dy/length*back*.5f,start.y+dy/length*back+dx/length*back*.5f),start,point(start.x+dx/length*back+dy/length*back*.5f,start.y+dy/length*back-dx/length*back*.5f)) else head
        }
        ShapeMode.Triangle, ShapeMode.Diamond, ShapeMode.Pentagon, ShapeMode.Hexagon, ShapeMode.Star -> {
            val n = when(mode) { ShapeMode.Triangle -> 3; ShapeMode.Diamond -> 4; ShapeMode.Pentagon -> 5; ShapeMode.Hexagon -> 6; else -> 10 }
            val vertices = (0 until n).map { i ->
                val angle = -PI/2+2*PI*i/n
                val r = if(mode==ShapeMode.Star && i%2==1) .42f else 1f
                point((left+right)/2+cos(angle).toFloat()*(right-left)/2*r,(top+bottom)/2+sin(angle).toFloat()*(bottom-top)/2*r)
            }
            vertices+vertices.first()
        }
        ShapeMode.Rectangle -> listOf(point(left,top),point(right,top),point(right,bottom),point(left,bottom),point(left,top))
        ShapeMode.Ellipse -> (0..48).map { i -> val angle = 2*PI*i/48; point((left+right)/2+cos(angle).toFloat()*(right-left)/2,(top+bottom)/2+sin(angle).toFloat()*(bottom-top)/2) }
    }
    return RecognizedShape((if (mode == ShapeMode.Auto) ShapeMode.Line else mode).name.lowercase(), points, 1f)
}

fun straightenHighlighter(points: List<InkPoint>): List<InkPoint> {
    if (points.size < 2) return points
    val first = points.first(); val last = points.last()
    if (abs(last.y-first.y) > abs(last.x-first.x)*.13f) return points
    val mid = (first.y+last.y)/2
    return listOf(first.copy(y=mid),last.copy(y=mid))
}

fun lassoSelect(strokes: List<InkStroke>, polygon: List<InkPoint>): Set<String> {
    if (polygon.size < 3) return emptySet()
    fun inside(p: InkPoint): Boolean {
        var result = false
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[(i+1)%polygon.size]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x) result = !result
        }
        return result
    }
    return strokes.filter { s -> !s.deleted && s.points.isNotEmpty() && s.points.count(::inside) >= s.points.size/2 }.map { it.id }.toSet()
}

/** Width is page-width normalized; both cursor axes and erasure use this same pixel radius. */
fun eraserRadiusPx(width: Float, pageWidthPx: Float): Float = width * pageWidthPx / 2

fun eraseStrokesOnPage(strokes: List<InkStroke>, path: List<InkPoint>, width: Float,
    pageWidthPx: Float, pageHeightPx: Float, mode: EraserMode, cachedBounds: Map<String, InkBounds> = emptyMap()): List<InkStroke> {
    val aspect = pageHeightPx / pageWidthPx.coerceAtLeast(1f)
    fun scaled(p: InkPoint) = p.copy(y = p.y * aspect)
    if (path.isEmpty()) return strokes
    val sweep = InkBounds(path.minOf { it.x }, path.minOf { it.y }, path.maxOf { it.x }, path.maxOf { it.y })
    return strokes.flatMap { original ->
        val bounds = cachedBounds[original.id] ?: listOf(original).bounds()
        val radius = (width + original.width) / 2
        if (original.deleted || bounds == null || bounds.right < sweep.left - radius || bounds.left > sweep.right + radius ||
            bounds.bottom < sweep.top - radius / aspect || bounds.top > sweep.bottom + radius / aspect) listOf(original)
        else {
            val result = eraseStrokes(listOf(original.copy(points = original.points.map(::scaled))), path.map(::scaled),
                eraserRadiusPx(width, pageWidthPx) / pageWidthPx.coerceAtLeast(1f), mode)
            result.map { stroke ->
                if (stroke.id == original.id) stroke.copy(points = original.points)
                else stroke.copy(points = stroke.points.map { p -> p.copy(y = p.y / aspect) })
            }
        }
    }
}

/** Auto uses wet ink during input; recognition is exclusively a pen-up operation. */
fun shapePreviewForPath(points: List<InkPoint>, mode: ShapeMode): List<InkPoint> =
    if (mode == ShapeMode.Auto || points.isEmpty()) emptyList() else explicitShape(points.first(),points.last(),mode).points

fun shapeForPath(points: List<InkPoint>, mode: ShapeMode): RecognizedShape =
    if (mode == ShapeMode.Auto) recognizeShape(points) ?: RecognizedShape("freehand",points,0f)
    else explicitShape(points.first(),points.last(),mode)
