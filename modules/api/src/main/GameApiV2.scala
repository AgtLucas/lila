package lila.api

import org.joda.time.DateTime
import play.api.libs.iteratee._
import play.api.libs.json._
import scala.concurrent.duration._

import chess.format.FEN
import chess.format.pgn.Tag
import lila.analyse.{ AnalysisRepo, JsonView => analysisJson, Analysis }
import lila.common.{ LightUser, MaxPerSecond }
import lila.game.JsonView._
import lila.game.PgnDump.WithFlags
import lila.game.{ Game, GameRepo, Query, PerfPicker }
import lila.user.User

final class GameApiV2(
    pgnDump: PgnDump,
    getLightUser: LightUser.Getter
)(implicit system: akka.actor.ActorSystem) {

  import GameApiV2._

  def exportOne(game: Game, config: OneConfig): Fu[String] =
    game.pgnImport ifTrue config.imported match {
      case Some(imported) => fuccess(imported.pgn)
      case None => enrich(config.flags)(game) flatMap {
        case (game, initialFen, analysis) => config.format match {
          case Format.JSON => toJson(game, initialFen, analysis, config.flags) map Json.stringify
          case Format.PGN => pgnDump.toPgnString(game, initialFen, analysis, config.flags)
        }
      }
    }

  private val fileR = """[\s,]""".r
  def filename(game: Game, format: Format): Fu[String] = gameLightUsers(game) map {
    case List(wu, bu) => fileR.replaceAllIn(
      "lichess_pgn_%s_%s_vs_%s.%s.%s".format(
        Tag.UTCDate.format.print(game.createdAt),
        pgnDump.dumper.player(game.whitePlayer, wu),
        pgnDump.dumper.player(game.blackPlayer, bu),
        game.id,
        format.toString.toLowerCase
      ), "_"
    )
  }

  def exportByUser(config: ByUserConfig): Enumerator[String] = {

    import reactivemongo.play.iteratees.cursorProducer
    import lila.db.dsl._

    val infiniteGames = GameRepo.sortedCursor(
      config.vs.fold(Query.user(config.user.id)) { vs =>
        Query.opponents(config.user, vs)
      } ++ Query.createdBetween(config.since, config.until),
      Query.sortCreated,
      batchSize = config.perSecond.value
    ).bulkEnumerator() &>
      lila.common.Iteratee.delay(1 second) &>
      Enumeratee.mapConcat(_.filter(config.postFilter).toSeq)

    val games = config.max.fold(infiniteGames) { max =>
      // I couldn't figure out how to do it properly :( :( :(
      var nb = 0
      infiniteGames &> Enumeratee.mapInput { in =>
        nb = nb + 1
        if (nb <= max) in
        else Input.EOF
      }
    }

    val formatter = config.format match {
      case Format.PGN => pgnDump.formatter(config.flags)
      case Format.JSON => jsonFormatter(config.flags)
    }

    games &> Enumeratee.mapM(enrich(config.flags)) &> formatter
  }

  private def enrich(flags: WithFlags)(game: Game) =
    GameRepo initialFen game flatMap { initialFen =>
      (flags.evals ?? AnalysisRepo.byGame(game)) map { analysis =>
        (game, initialFen, analysis)
      }
    }

  private def jsonFormatter(flags: WithFlags) =
    Enumeratee.mapM[(Game, Option[FEN], Option[Analysis])].apply[String] {
      case (game, initialFen, analysis) => toJson(game, initialFen, analysis, flags) map { json =>
        s"${Json.stringify(json)}\n"
      }
    }

  private def toJson(
    g: Game,
    initialFen: Option[FEN],
    analysisOption: Option[Analysis],
    withFlags: WithFlags
  ): Fu[JsObject] = gameLightUsers(g) map { lightUsers =>
    Json.obj(
      "id" -> g.id,
      "rated" -> g.rated,
      "variant" -> g.variant.key,
      "speed" -> g.speed.key,
      "perf" -> PerfPicker.key(g),
      "createdAt" -> g.createdAt,
      "lastMoveAt" -> g.movedAt,
      "status" -> g.status.name,
      "players" -> JsObject(g.players zip lightUsers map {
        case (p, user) => p.color.name -> Json.obj()
          .add("user", user)
          .add("rating", p.rating)
          .add("ratingDiff", p.ratingDiff)
          .add("name", p.name)
          .add("provisional" -> p.provisional)
          .add("analysis" -> analysisOption.flatMap(analysisJson.player(g pov p.color)))
        // .add("moveCentis" -> withFlags.moveTimes ?? g.moveTimes(p.color).map(_.map(_.centis)))
      })
    ).add("initialFen" -> initialFen.map(_.value))
      .add("winner" -> g.winnerColor.map(_.name))
      .add("opening" -> g.opening.ifTrue(withFlags.opening))
      .add("moves" -> withFlags.moves.option(g.pgnMoves mkString " "))
      .add("daysPerTurn" -> g.daysPerTurn)
      .add("analysis" -> analysisOption.ifTrue(withFlags.evals).map(analysisJson.moves(_, withGlyph = false)))
      .add("clock" -> g.clock.map { clock =>
        Json.obj(
          "initial" -> clock.limitSeconds,
          "increment" -> clock.incrementSeconds,
          "totalTime" -> clock.estimateTotalSeconds
        )
      })
  }

  private def gameLightUsers(game: Game): Fu[List[Option[LightUser]]] =
    (game.whitePlayer.userId ?? getLightUser) zip (game.blackPlayer.userId ?? getLightUser) map {
      case (wu, bu) => List(wu, bu)
    }
}

object GameApiV2 {

  sealed trait Format
  object Format {
    case object PGN extends Format
    case object JSON extends Format
  }

  sealed trait Config {
    val format: Format
  }

  case class OneConfig(
      format: Format,
      imported: Boolean,
      flags: WithFlags
  ) extends Config

  case class ByUserConfig(
      user: User,
      vs: Option[User],
      format: Format,
      since: Option[DateTime] = None,
      until: Option[DateTime] = None,
      max: Option[Int] = None,
      rated: Option[Boolean] = None,
      perfType: Set[lila.rating.PerfType],
      analysed: Option[Boolean] = None,
      color: Option[chess.Color],
      flags: WithFlags,
      perSecond: MaxPerSecond
  ) extends Config {
    def postFilter(g: Game) =
      rated.fold(true)(g.rated ==) && {
        perfType.isEmpty || g.perfType.exists(perfType.contains)
      } && color.fold(true) { c =>
        g.player(c).userId has user.id
      } && analysed.fold(true)(g.metadata.analysed ==)
  }
}
