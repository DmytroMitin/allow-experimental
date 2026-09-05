package m0.overrideprovidernegative

import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

trait Base:
  def foo(): Int

final class Implementation extends Base:
  @experimental
  override def foo(): Int = 1

  @allowExperimental
  def bar(): Int = foo()
