package dotty.tools

import dotc.core.Contexts.Context
import dotc.core.Symbols.Symbol
import dotc.reporting.diagnostic.MessageContainer
import dotc.printing.ReplPrinter
import dotc.reporting.{HideNonSensicalMessages, StoreReporter, UniqueMessagePositions}

package object repl {
  /** Create empty outer store reporter */
  private[repl] def storeReporter: StoreReporter =
    new StoreReporter(null)
    with UniqueMessagePositions with HideNonSensicalMessages

  private[repl] implicit class ShowUser(val s: Symbol) extends AnyVal {
    def showUser(implicit ctx: Context): String = {
      val printer = new ReplPrinter(ctx)
      val text = printer.dclText(s)
      text.mkString(ctx.settings.pageWidth.value, ctx.settings.printLines.value)
    }
  }

  private[repl] implicit class StoreReporterContext(val ctx: Context) extends AnyVal {
    def flushBufferedMessages(): List[MessageContainer] =
      ctx.reporter match {
        case rep: StoreReporter => rep.removeBufferedMessages(ctx)
        case _ => Nil
      }
  }
}
