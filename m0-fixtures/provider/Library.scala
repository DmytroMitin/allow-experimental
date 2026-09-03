package m0

import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

@experimental
def foo(): Int = 1

@allowExperimental
def bar(): Int = foo()

def baz(): Int = bar()
