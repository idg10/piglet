name := "PigParser"

lazy val commonSettings = Seq(
  version := "1.0",
  scalaVersion := "2.11.6",
  organization := "dbis"
)

// scalaVersion := "2.11.6"

// scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

/*
 * define the backend for the compiler: currently we support spark and flink
 */
val backend = "spark"

def backendDependencies(backend: String) = backend match {
  case "flink" => "org.apache.spark" %% "spark-core" % "1.3.0" % "provided"
  case "spark" => "org.apache.spark" %% "spark-core" % "1.3.0"
}


lazy val root = (project in file(".")).
  configs(IntegrationTest).
  settings(commonSettings: _*).
  settings(Defaults.itSettings: _*).
  settings(
    libraryDependencies ++= Seq(
      "jline" % "jline" % "2.12.1",
      "org.scalatest" % "scalatest_2.11" % "2.2.0" % "test,it" withSources(),
      "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.3" withSources(),
      "org.scala-lang" % "scala-compiler" % "2.11.6",
      "com.assembla.scala-incubator" %% "graph-core" % "1.9.1",
      backendDependencies(backend),
      // "org.apache.spark" %% "spark-core" % "1.3.0" % "provided",
      "com.github.scopt" %% "scopt" % "3.3.0",
      "com.github.scala-incubator.io" % "scala-io-file_2.11" % "0.4.3-1"
    )
  ).
  aggregate(sparklib).
  dependsOn(sparklib)

lazy val sparklib = (project in file("sparklib")).
  settings(commonSettings: _*).
  settings(
    libraryDependencies ++= Seq(
      "org.scalatest" % "scalatest_2.11" % "2.2.0" % "test" withSources(),
      "org.scala-lang" % "scala-compiler" % "2.11.6",
      "org.apache.spark" %% "spark-core" % "1.3.0"
    // other settings
    )
  )

mainClass in (Compile, packageBin) := Some("dbis.pig.PigREPL")

mainClass in (Compile, run) := Some("dbis.pig.PigCompiler")

assemblyJarName in assembly := "PigCompiler.jar"

test in assembly := {}

mainClass in assembly := Some("dbis.pig.PigCompiler")

