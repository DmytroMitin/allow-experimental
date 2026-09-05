package m0.inlinenegative

import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

@experimental
def foo(): Int = 1

@allowExperimental
inline def frontend(): Int = foo()
