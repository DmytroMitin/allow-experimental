# allow-experimental

A Scala 3 compiler plugin

```scala
@experimental
def foo() = ...

@experimental // has to be `@experimental` too
def bar() =
  foo()

@experimental // has to be `@experimental` too
def baz() =
  bar()

// use of `@allowExperimental` --->

@experimental
def foo() = ...

@allowExperimental // no need to be `@experimental`
def bar() =
  foo()

// no need to be @experimental
def baz() =
  bar()
```