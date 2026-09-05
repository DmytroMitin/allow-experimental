package m0.directnegative

import scala.annotation.experimental

@experimental
def foo(): Int = 1

def bad(): Int = foo()
