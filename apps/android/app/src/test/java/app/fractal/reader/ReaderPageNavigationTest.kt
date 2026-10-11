package app.fractal.reader
import org.testng.Assert.*
import org.testng.annotations.Test
class ReaderPageNavigationTest {
 @Test fun jumpClampsAndRejectsInvalidInput() {
  assertEquals(pageJumpIndex("12",40),11)
  assertEquals(pageJumpIndex("-3",40),0)
  assertEquals(pageJumpIndex("9999999999",40),39)
  assertNull(pageJumpIndex("text",40))
  assertNull(pageJumpIndex("1.2",40))
  assertNull(pageJumpIndex("1",0))
 }
 @Test fun scrollbarMapsEveryPageAndClampsEndpoints() {
  for (total in listOf(1,2,40,300)) for (page in 0 until total)
   assertEquals(pageAtScrollPosition(pageScrollPosition(page,0.0,total),total),page)
  assertEquals(pageAtScrollPosition(-1f,40),0)
  assertEquals(pageAtScrollPosition(2f,40),39)
  assertEquals(pageScrollPosition(39,1.0,40),1f)
  assertTrue(pageScrollPosition(10,.5,40)>pageScrollPosition(10,0.0,40))
  assertEquals(pageAtScrollPosition(1f,0),0)
 }
}
