package m0.overriddenprovidernegative

import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

trait Base:
  @experimental
  def foo(): Int

final class Implementation extends Base:
  @experimental
  override def foo(): Int = 1

@allowExperimental
def bar(base: Base): Int = base.foo()
