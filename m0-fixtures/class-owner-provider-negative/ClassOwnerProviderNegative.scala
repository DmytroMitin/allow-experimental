package m0.classownerprovidernegative

import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

@experimental
final class Provider:
  def foo(): Int = 1

@allowExperimental
def bar(provider: Provider): Int = provider.foo()
