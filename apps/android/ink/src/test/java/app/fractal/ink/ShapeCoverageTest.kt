package app.fractal.ink
import org.testng.Assert.*
import org.testng.annotations.Test
import kotlin.math.*
class ShapeCoverageTest {
 private fun p(x:Float,y:Float)=InkPoint(x,y,.5f,0)
 private fun sample(v:List<InkPoint>,closed:Boolean=true):List<InkPoint> {
  val out=v.zipWithNext().flatMap{(a,b)->(0 until 35).map{i->
   val f=i/35f;val x=a.x+(b.x-a.x)*f-.5f;val y=a.y+(b.y-a.y)*f-.5f
   p(.5f+x*cos(.37f)-y*sin(.37f)+sin(i*1.7f)*.0007f,.5f+x*sin(.37f)+y*cos(.37f)+cos(i*1.3f)*.0007f)
  }}
  return if(closed) out.dropLast(2) else out
 }
 @Test fun closedShapes() {
  for((mode,type) in listOf(ShapeMode.Triangle to "triangle",ShapeMode.Rectangle to "rectangle",ShapeMode.Diamond to "rectangle",ShapeMode.Pentagon to "pentagon",ShapeMode.Hexagon to "hexagon",ShapeMode.Star to "star"))
   assertEquals(recognizeShape(sample(explicitShape(p(.2f,.2f),p(.8f,.8f),mode).points))?.type,type,"$mode")
  val heptagon=(0..7).map{i->val a=2*PI*i/7;p(.5f+cos(a).toFloat()*.25f,.5f+sin(a).toFloat()*.25f)}
  assertEquals(recognizeShape(sample(heptagon))?.type,"polygon")
 }
 @Test fun quadrilaterals() {
  val rhombus=listOf(p(.2f,.5f),p(.5f,.3f),p(.8f,.5f),p(.5f,.7f),p(.2f,.5f))
  assertEquals(recognizeShape(sample(rhombus))?.type,"diamond")
  for(v in listOf(listOf(p(.2f,.2f),p(.7f,.2f),p(.8f,.5f),p(.3f,.5f),p(.2f,.2f)),listOf(p(.3f,.2f),p(.6f,.2f),p(.8f,.7f),p(.2f,.7f),p(.3f,.2f))))
   assertEquals(recognizeShape(sample(v))?.type,"quadrilateral")
 }
 @Test fun rotatedEllipseAndCircle() {
  for(ry in listOf(.25f,.13f)) {
   val raw=(0..180).map{i->val a=2*PI*i/180;val x=cos(a).toFloat()*.25f;val y=sin(a).toFloat()*ry
    p(.5f+x*cos(.63f)-y*sin(.63f)+sin(i.toFloat())*.001f,.5f+x*sin(.63f)+y*cos(.63f))}
   assertEquals(recognizeShape(raw)?.type,"ellipse")
  }
 }
 @Test fun openShapes() {
  val zig=listOf(p(.1f,.2f),p(.3f,.6f),p(.5f,.2f),p(.7f,.6f))
  assertEquals(recognizeShape(sample(zig,false))?.type,"polyline")
  for(mode in listOf(ShapeMode.Arrow,ShapeMode.DoubleArrow))
   assertEquals(recognizeShape(sample(explicitShape(p(.15f,.5f),p(.85f,.5f),mode).points,false))?.type,mode.name.lowercase())
 }
 @Test fun curvesAndScribblesStayRaw() {
  val arc=(0..120).map{i->val a=PI*i/180;p(.5f+cos(a).toFloat()*.25f,.5f+sin(a).toFloat()*.25f)}
  val scribble=(0..300).map{i->p(.5f+sin(i*.17f)*.2f,.5f+sin(i*.31f)*.18f)}
  val letter=(0..180).map{i->val a=PI*.25+PI*1.5*i/180;p(.5f+cos(a).toFloat()*.2f,.5f+sin(a).toFloat()*.25f)}
  for(raw in listOf(arc,scribble,letter)) {assertNull(recognizeShape(raw));assertEquals(shapeForPath(raw,ShapeMode.Auto).points,raw)}
 }
 @Test fun autoMoveNeverSnaps() {
  val raw=sample(explicitShape(p(.2f,.2f),p(.8f,.8f),ShapeMode.Triangle).points)
  for(i in 1..raw.size) assertTrue(shapePreviewForPath(raw.take(i),ShapeMode.Auto).isEmpty())
  assertEquals(shapeForPath(raw,ShapeMode.Auto).type,"triangle")
 }
 @Test fun overshootAndRegularization() {
  val rectangle=sample(explicitShape(p(.2f,.2f),p(.7f,.71f),ShapeMode.Rectangle).points)
  val shape=recognizeShape(rectangle+rectangle.first().copy(x=rectangle.first().x-.01f))!!
  assertEquals(shape.type,"rectangle")
  fun length(a:InkPoint,b:InkPoint)=hypot(a.x-b.x,a.y-b.y)
  assertEquals(length(shape.points[0],shape.points[1]),length(shape.points[1],shape.points[2]),.0001f)
  val right=recognizeShape(sample(listOf(p(.2f,.2f),p(.7f,.21f),p(.21f,.7f),p(.2f,.2f))))!!
  val a=right.points[0];val b=right.points[1];val c=right.points[2]
  val dots=listOf((b.x-a.x)*(c.x-a.x)+(b.y-a.y)*(c.y-a.y),
   (a.x-b.x)*(c.x-b.x)+(a.y-b.y)*(c.y-b.y),(a.x-c.x)*(b.x-c.x)+(a.y-c.y)*(b.y-c.y))
  assertTrue(dots.minOf{abs(it)}<.00001f)
 }
 @Test fun snapCommitsAsSingleUndoChange() {
  val state=InkPageState()
  val raw=sample(explicitShape(p(.2f,.2f),p(.8f,.8f),ShapeMode.Triangle).points)
  state.beginGesture()
  assertTrue(state.strokes.isEmpty())
  val snapped=shapeForPath(raw,ShapeMode.Auto)
  state.previewGesture(listOf(InkStroke(paperKey="test",page=1,deviceId="test",updatedAt="",color="#000000",width=.003f,points=snapped.points,shape=InkShape(snapped.type))))
  state.finishGesture(true)
  assertEquals(state.strokes.size,1)
  assertTrue(state.undo()); assertTrue(state.strokes.none{!it.deleted})
  assertTrue(state.redo()); assertEquals(state.strokes.count{!it.deleted},1)
 }
 @Test fun boundedRecognitionTiming() {
  val raw=(0 until 500).map{i->val a=2*PI*i/499;p(.5f+cos(a).toFloat()*.2f,.5f+sin(a).toFloat()*.15f)}
  repeat(100){recognizeShape(raw)}
  val start=System.nanoTime()
  repeat(1000){recognizeShape(raw)}
  println("500-point ellipse recognition mean: ${(System.nanoTime()-start)/1_000_000.0/1000} ms (host JVM; device timing unverified)")
 }

}
