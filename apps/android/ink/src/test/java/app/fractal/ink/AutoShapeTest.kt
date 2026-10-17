package app.fractal.ink
import org.testng.Assert.*
import org.testng.annotations.Test
import kotlin.math.*
class AutoShapeTest {
 private fun p(x: Float,y: Float) = InkPoint(x,y,.5f,0)
 @Test fun roughRectangleRecognizedInAuto() {
  val corners = listOf(p(.2f,.2f),p(.7f,.21f),p(.69f,.7f),p(.21f,.69f),p(.2f,.2f))
  val path = corners.zipWithNext().flatMap { (a,b) -> (0..12).map { i ->
   val f = i/12f; p(a.x+(b.x-a.x)*f,a.y+(b.y-a.y)*f+sin(i.toFloat())*.002f)
  } }
  assertEquals(shapeForPath(path,ShapeMode.Auto).type,"rectangle")
 }
 @Test fun roughCircleRecognizedInAuto() {
  val path = (0..60).map { i -> val a = 2*PI*i/60; val r = .2+sin(i.toDouble())*.003
   p((.5+cos(a)*r).toFloat(),(.5+sin(a)*r).toFloat()) }
  assertEquals(shapeForPath(path,ShapeMode.Auto).type,"ellipse")
 }
 @Test fun nearStraightStrokeAndFallbackStaysRaw() {
  assertEquals(shapeForPath((0..20).map { p(.1f+it*.02f,.3f+sin(it.toFloat())*.002f) },ShapeMode.Auto).type,"line")
  assertEquals(shapeForPath(listOf(p(.1f,.1f),p(.2f,.3f)),ShapeMode.Auto).type,"freehand")
  assertEquals(InkToolState().shapeMode,ShapeMode.Auto)
 }
 @Test fun explicitRectangleStillUsesCorners() {
  val shape = shapeForPath(listOf(p(.7f,.8f),p(.2f,.3f)),ShapeMode.Rectangle)
  assertEquals(shape.type,"rectangle"); assertEquals(shape.points.size,5)
 }
}
