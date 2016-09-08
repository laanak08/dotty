
object Test {
  import CanPrintPhantoms._

  type Ctx[T] = implicit CanPrint => T

  def contextualPrintln(s: String): Ctx[Unit] = implicit (canPrint: CanPrint) => println(s)

  def main(args: Array[String]) = {
    implicit def ev: CanPrint = canPrint
    contextualPrintln("abc")
  }
}

object CanPrintPhantoms extends Phantom {
  type CanPrint <: this.Any
  def canPrint: CanPrint = assume
}