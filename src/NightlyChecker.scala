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

    val scala3Versions = cache
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
      .sorted

    val code = options.nightlyCheckerCode
    val scala3stable = scala3Versions
      .filterNot(_.endsWith("-NIGHTLY"))
      .filterNot(_.contains("RC"))
      .reverse

    val (latestNotWorkingStable, latestWorkingStableOpt) =
      searchLatestStableWorkingVersion(code, scala3stable)

    val scala3nigthlies =
      latestWorkingStableOpt match {
        case Some(latestWorkingStable) =>
          scala3Versions
            .filter(_.endsWith("-NIGHTLY"))
            .filter(_.startsWith(latestWorkingStable))
        case None =>
          scala3Versions
            .filter(_.endsWith("-NIGHTLY"))
            .dropWhile(!_.startsWith(latestNotWorkingStable))
      }

    val latestWorkingNightly =
      searchNigthlyStableWorkingVersion(code, scala3nigthlies)
        .getOrElse {
          println("Not found nightly scala version working with input code")
          sys.exit(0)
        }

    println(
      s"Found the latest nightly version working with the snippet code: $latestWorkingNightly"
    )
  }

  private def searchNigthlyStableWorkingVersion(
      code: String,
      scala3Versions: List[String]
  ): Option[String] = {
    val halfIndex = Math.ceil((scala3Versions.length) / 2).toInt
    if (scala3Versions.length == 1) Some(scala3Versions.head)
    else if (isCorrectScalaVersion(scala3Versions(halfIndex), code)) {
      searchNigthlyStableWorkingVersion(code, scala3Versions.drop(halfIndex))
    } else
      searchNigthlyStableWorkingVersion(code, scala3Versions.take(halfIndex))
  }

  private def searchLatestStableWorkingVersion(
      code: String,
      scala3Versions: List[String]
  ): (String, Option[String]) = {
    val (stableVersion, stableIndexVersionIndex) = scala3Versions.zipWithIndex
      .collectFirst {
        case (stable, index) if isCorrectScalaVersion(stable, code) =>
          (stable, index - 1)
      }
      .getOrElse {
        println("Not found stable scala version working with input code")
        sys.exit(0)
      }

    val stableIndexVersion =
      if (stableIndexVersionIndex < 0) None
      else Some(scala3Versions(stableIndexVersionIndex))
    (stableVersion, stableIndexVersion)
  }

  private def isCorrectScalaVersion(version: String, code: String): Boolean = {
    val res = os
      .proc("scala-cli", "compile", "-S", version, "-")
      .call(
        cwd = os.pwd,
        stdin = code,
        check = false,
        mergeErrIntoOut = true
      )
    val output = res.out.text().trim
    res.exitCode == 0
  }
}
