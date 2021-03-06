package zio3d.game

import java.util.concurrent.TimeUnit

import zio.blocking.blocking
import zio.clock.currentTime
import zio.{ZIO, ZLayer}
import zio3d.core.glfw.{Window, WindowSize}
import zio3d.core.{CoreEnv, gl}
import zio3d.engine._
import zio3d.engine.loaders.LoadingError
import zio3d.engine.runtime.MainThreadApp
import zio3d.engine.runtime.MainThreadApp.mainThread
import zio3d.game.hud.HudRenderer

object Main extends MainThreadApp {

  val gameEnv: ZLayer[Any, Nothing, GameEnv] =
    RenderEnv.live ++
      (CoreEnv.live >>> HudRenderer.live)

  def run(args: List[String]) =
    Runner.runGame(Zio3dGame).provideLayer(gameEnv).either
      .map(_.fold(err => { println(s"Error: $err"); 1 }, _ => 0))
}

object Runner {
  val title        = "Zio3D"
  val windowWidth  = 400
  val windowHeight = 400

  def runGame[R, S](game: Game[R, S]): ZIO[GameEnv, LoadingError, Unit] =
    glwindow
      .open(title, windowWidth, windowHeight)
      .lock(mainThread)
      .bracket(
        w => glwindow.close(w).lock(mainThread),
        window =>
          for {
            c   <- game.initRenderer.lock(mainThread)
            s   <- game.initialState(c).lock(mainThread)
            now <- currentTime(TimeUnit.MILLISECONDS)
            _   <- gameLoop(window, game, c, s, None, now)
            _   <- game.cleanup(c, s).lock(mainThread)
          } yield ()
      )

  import zio3d.core.glfw

  private def gameLoop[R, S](
    window: Window,
    game: Game[R, S],
    r: R,
    st: S,
    i: Option[UserInput],
    t: Long
  ): ZIO[GameEnv, Nothing, Unit] =
    for {
      _ <- glfw.pollEvents.lock(mainThread)
      s <- glfw.getWindowSize(window).lock(mainThread)
      i <- glwindow.getUserInput(window, i)
      _ <- blocking(render(window, game, s, r, st))
      t <- currentTime(TimeUnit.MILLISECONDS)
      n <- game.nextState(st, i, t)
      _ <- glfw.swapBuffers(window).lock(mainThread)
      x <- glfw.windowShouldClose(window).lock(mainThread)
      _ <- gameLoop(window, game, r, n._1, Some(i), t).when(n._2 && !x)
    } yield ()

  private def render[R, S](
    window: Window,
    game: Game[R, S],
    windowSize: WindowSize,
    renderer: R,
    state: S
  ) =
    for {
      _ <- glfw.makeContextCurrent(window)
      _ <- gl.createCapabilities
      n <- game.render(windowSize, renderer, state)
    } yield n

}
