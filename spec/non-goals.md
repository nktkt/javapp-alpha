# Non-Goals

Java++ should be powerful without importing the dangerous parts of C++.

The project explicitly does not try to:

- replace the JVM
- replace the Java standard library
- replace Java source files
- target Kotlin or Scala compatibility as a primary goal
- add arbitrary pointer arithmetic
- add C++-style implicit conversions
- allow global operator pollution
- support stateful multiple inheritance
- support unbounded textual macros
- normalize runtime reflection as the main extension mechanism
- make final/private fields casually mutable
- make every feature zero-cost at MVP stage

The MVP also does not try to implement:

- specialized generics
- a complete ownership checker
- a complete macro system
- direct bytecode generation
- a production IDE plugin
- full AOT profile optimization

The first alpha should make Java++ concrete and testable, not complete.

