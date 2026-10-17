package app.fractal.ink

import kotlin.math.*
import kotlin.random.Random
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/** Fixed-seed, shape-relative errors; no pixel-frequency independent jitter.
 * Rounded corners and bowed edges are built before unequal event sampling.
 * Smooth drift is then applied along measured arc length, independently of speed.
 */
class HandDrawnShapeTest {
    private fun p(x: Float, y: Float) = InkPoint(x, y, .5f, 0)
    private fun mix(a: InkPoint,b: InkPoint,t: Float)=p(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t)
    private fun d(a: InkPoint,b: InkPoint)=hypot(a.x-b.x,a.y-b.y)
    private fun vertices(name: String): List<InkPoint> {
        if(name=="line") return listOf(p(.2f,.5f),p(.8f,.5f))
        if(name=="diamond") return listOf(p(.2f,.5f),p(.5f,.32f),p(.8f,.5f),p(.5f,.68f))
        if(name=="rectangle" || name=="square") {
            val y=if(name=="square") .2f else .32f
            return listOf(p(.2f,y),p(.8f,y),p(.8f,1-y),p(.2f,1-y))
        }
        if(name=="arrow" || name=="doublearrow") return explicitShape(p(.2f,.5f),p(.8f,.5f),if(name=="arrow") ShapeMode.Arrow else ShapeMode.DoubleArrow).points
        val n=when(name){"triangle"->3;"pentagon"->5;"hexagon"->6;"star"->10;else->120}
        return (0 until n).map { i->
            val a=-PI/2+2*PI*i/n
            val radius=if(name=="star" && i%2==1) .42f else 1f
            p(.5f+.3f*cos(a).toFloat()*radius,.5f+(if(name=="ellipse") .18f else .3f)*sin(a).toFloat()*radius)
        }
    }
    private fun drawing(name:String, noise:Float, seed:Int):List<InkPoint> {
        val r=Random(seed)
        val closed=name !in listOf("line","arrow","doublearrow")
        val v=vertices(name)
        val size=max(v.maxOf{it.x}-v.minOf{it.x},v.maxOf{it.y}-v.minOf{it.y})
        val diagonal=hypot(v.maxOf{it.x}-v.minOf{it.x},v.maxOf{it.y}-v.minOf{it.y})
        val radius=.05f+r.nextFloat()*.05f
        val curve=name=="circle" || name=="ellipse"
        val base=mutableListOf<InkPoint>()
        fun addEdge(a:InkPoint,b:InkPoint) {
            val count=8+r.nextInt(12)
            val bow=(r.nextFloat()*2-1)*size*.008f
            val len=d(a,b).coerceAtLeast(.00001f)
            val speed=.7f+r.nextFloat()*.6f
            for(i in 0 until count) {
                val t=(i.toFloat()/count).pow(speed)
                val q=mix(a,b,t)
                base.add(p(q.x-(b.y-a.y)/len*bow*sin(PI*t).toFloat(),q.y+(b.x-a.x)/len*bow*sin(PI*t).toFloat()))
            }
        }
        if(curve) {
            for(i in 0..360) {
                val a=2*PI*i/360
                // Slight flat spots: a hand-drawn circle is not a perfect conic.
                val x=cos(a).toFloat().coerceIn(-.96f,.96f)
                base.add(p(.5f+.3f*x,.5f+(if(name=="ellipse") .18f else .3f)*sin(a).toFloat()))
            }
        } else {
            val incoming=v.indices.map { i ->
                if(!closed && i==0) v[i] else {
                    val prev=v[(i+v.size-1)%v.size];val next=v[(i+1)%v.size]
                    mix(v[i],prev,radius*min(d(v[i],prev),d(v[i],next))/d(v[i],prev).coerceAtLeast(.00001f))
                }
            }
            val outgoing=v.indices.map { i ->
                if(!closed && i==v.lastIndex) v[i] else {
                    val prev=v[(i+v.size-1)%v.size];val next=v[(i+1)%v.size]
                    mix(v[i],next,radius*min(d(v[i],prev),d(v[i],next))/d(v[i],next).coerceAtLeast(.00001f))
                }
            }
            val edges=if(closed)v.size else v.size-1
            for(i in 0 until edges) {
                val j=(i+1)%v.size
                addEdge(outgoing[i],incoming[j])
                // Dense events during a slow, rounded turn.
                val count=18+r.nextInt(18)
                for(k in 0 until count) {
                    val t=k.toFloat()/count
                    base.add(mix(mix(incoming[j],v[j],t),mix(v[j],outgoing[j],t),t))
                }
            }
            if(!closed) base.add(v.last())
        }
        // Start closed shapes in the middle of an edge, rather than at a corner.
        var raw=if(closed) {val seam=base.size/(if(curve)7 else v.size*2);base.drop(seam)+base.take(seam)} else base.toList()
        if(closed) {
            raw=raw+raw.first()
            val amount=diagonal*(.05f+r.nextFloat()*.10f)
            var traveled=0f
            if(seed%2==0) {
                var end=raw.lastIndex
                while(end>5 && traveled<amount){traveled+=d(raw[end],raw[end-1]);end--}
                raw=raw.take(end+1)
            } else {
                val extra=mutableListOf<InkPoint>()
                for(i in 1 until raw.size) {traveled+=d(raw[i-1],raw[i]);extra.add(raw[i]);if(traveled>=amount)break}
                raw=raw+extra
            }
        }
        val phase=FloatArray(6){r.nextFloat()*2*PI.toFloat()}
        val angle=r.nextFloat()*2*PI.toFloat()
        val lengths=mutableListOf(0f)
        raw.zipWithNext().forEach{(a,b)->lengths.add(lengths.last()+d(a,b))}
        val total=lengths.last()
        fun drift(t:Float,offset:Int)=.60f*sin(2*PI.toFloat()*1.3f*t+phase[offset])+.28f*sin(2*PI.toFloat()*3.7f*t+phase[offset+1])+.12f*sin(2*PI.toFloat()*6.1f*t+phase[offset+2])
        return raw.mapIndexed { i,q ->
            val t=lengths[i]/total
            val x=q.x-.5f+size*noise*drift(t,0)
            val y=q.y-.5f+size*noise*drift(t,3)
            p(.5f+x*cos(angle)-y*sin(angle),.5f+x*sin(angle)+y*cos(angle))
        }
    }

    @Test fun realisticDrawingAccuracy() {
        val failures=mutableListOf<String>()
        println("shape | 2% wobble | 3% wobble (50 trials each)")
        for(name in listOf("line","arrow","doublearrow","triangle","rectangle","square","diamond","pentagon","hexagon","star","circle","ellipse")) {
            val counts=listOf(.02f,.03f).map { noise ->
                val expected=when(name){"square"->"rectangle";"circle"->"ellipse";else->name}
                val mistakes=mutableMapOf<String,Int>()
                val count=(0 until 50).count { trial ->
                    val raw=drawing(name,noise,0x51A9+trial*71+name.hashCode())
                    val result=recognizeShape(raw)?.type
                    if(result!=expected) mistakes[result?:"freehand"]=(mistakes[result?:"freehand"]?:0)+1
                    result==expected
                }
                if(count<(if(noise==.02f)45 else 40)) failures.add("$name $noise: $count/50, $mistakes")
                count
            }
            println("$name | ${counts[0]*2}% | ${counts[1]*2}%")
        }
        assertTrue(failures.isEmpty(),failures.joinToString("\n"))
    }

    @Test fun realisticNegatives() {
        val failures=mutableListOf<String>()
        for(name in listOf("scribble","e","l","arc","spiral")) {
            val mistakes=mutableMapOf<String,Int>()
            val rejected=(0 until 100).count { trial ->
                val r=Random(0x891+trial*31);val angle=r.nextFloat()*2*PI.toFloat()
                val scribbleFrequency=8+r.nextInt(3)
                val raw=(0..300).map { i ->
                    val t=i/300f
                    val pair=when(name) {
                        "scribble" -> .24f*sin(t*PI.toFloat()*scribbleFrequency) to .22f*sin(t*PI.toFloat()*13)
                        "e" -> {val a=-PI.toFloat()/3+t*PI.toFloat()*2.2f;(.2f*cos(a)+.18f*t) to (.2f*sin(a)+.12f*t)}
                        "l" -> {val a=-PI.toFloat()/2+t*PI.toFloat()*2.3f;(.065f*cos(a)+.12f*t) to (.29f*sin(a)+.22f*t)}
                        "spiral" -> {val a=t*PI.toFloat()*4.5f;(.03f+.23f*t)*cos(a) to (.03f+.23f*t)*sin(a)}
                        else -> {val a=t*PI.toFloat()*.30f; .3f*cos(a) to .3f*sin(a)}
                    }
                    val x=pair.first+.006f*sin(t*PI.toFloat()*5+trial)
                    val y=pair.second+.006f*sin(t*PI.toFloat()*7+trial)
                    p(.5f+x*cos(angle)-y*sin(angle),.5f+x*sin(angle)+y*cos(angle))
                }
                val type=recognizeShape(raw)?.type
                if(type!=null) mistakes[type]=(mistakes[type]?:0)+1
                type==null
            }
            println("negative $name: $rejected/100 freehand $mistakes")
            if(rejected<95) failures.add("$name: $rejected/100")
        }
        assertTrue(failures.isEmpty(),failures.joinToString("\n"))
    }
}
