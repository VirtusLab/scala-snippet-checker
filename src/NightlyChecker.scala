//> using lib "io.get-coursier:coursier_2.13:2.1.0-M5-24-g678b31710"
//> using lib "com.lihaoyi::os-lib:0.8.0"
//> using lib "com.github.alexarchambault:case-app_2.13:2.1.0-M13"
//> using scala "2.13"

import coursier.Versions
import coursier.cache.FileCache
import coursier.core.{Version, Versions => CoreVersions}
import coursier.util.{Artifact, Task}
import coursier.util.StringInterpolators.safeModule
import coursier.core.Module

import scala.concurrent.duration.DurationInt
import scala.language.experimental.macros
import coursier.core.Organization
import coursier.core.ModuleName
import caseapp._

case class Options(
    nightlyCheckerCode: String
)
object Options {
  implicit lazy val parser: Parser[Options] = Parser.derive
}

object NightlyChecker extends CaseApp[Options] {

  def run(options: Options, arg: RemainingArgs): Unit = {
    val cache: FileCache[Task] = FileCache()
    val scala3Library = Module.apply(
      Organization("org.scala-lang"),
      ModuleName("scala3-library_3"),
      Map.empty
    )

    val scala3nigthlies: List[String] = cache
      .withTtl(0.seconds)
      .logger
      .use {
        Versions(cache)
          .withModule(scala3Library)
          .result()
          .unsafeRun()(cache.ec)
      }
      .versions
      .available
      .filter(_.endsWith("-NIGHTLY"))
      .sorted
      .reverse

    for { nightly <- scala3nigthlies } {
      val res = os
        .proc("scala-cli", "-S", nightly, "-q", "-")
        .call(
          cwd = os.pwd,
          stdin = options.nightlyCheckerCode,
          check = false,
          mergeErrIntoOut = true
        )
      val output = res.out.text().trim
      if (res.exitCode == 0) {
        println(
          s"Found the latest nightly version working with the snippet code: $nightly"
        )
        sys.exit(0)
      }
    }
  }
}
