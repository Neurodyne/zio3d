package zio3d.game

import zio.ZIO
import zio.random.Random
import zio3d.core.math.Vector3
import zio3d.engine._
import zio3d.game.hud.HudRenderer.HudState

import scala.annotation.tailrec

trait Scene {

  def skyboxItems: List[GameItem]

  def sceneItems: List[GameItem]

  def simpleItems: List[GameItem]

  def particles: List[GameItem]

  def fixtures: Fixtures
}

final case class GameState(
  time: Long,
  terrain: Terrain,
  skybox: GameItem,
  monsters: List[GameItem],
  simpleObjects: List[GameItem],
  sceneObjects: List[GameItem],
  baseFire: Fire, // template for creating more fires
  fires: List[Fire],
  gun: Gun,
  camera: Camera,
  ambientLight: Vector3,
  flashLight: SpotLight,
  fog: Fog,
  hud: HudState
) extends Scene {

  // per millisecond
  final val moveSpeed        = 0.005f
  final val cameraHeight     = 1.8f
  final val mouseSensitivity = 5.0f

  override def skyboxItems: List[GameItem] = List(skybox)

  override def sceneItems = monsters ++ sceneObjects ++ terrain.blocks

  override def simpleItems = simpleObjects

  override def particles: List[GameItem] = fires.flatMap(_.renderItems) ++ gun.renderItems

  override def fixtures = Fixtures(LightSources(ambientLight, None, Nil, List(flashLight)), fog)

  def nextState(userInput: UserInput, currentTime: Long): ZIO[Random, Nothing, GameState] = {
    val elapsedMillis                                   = currentTime - time
    val (survivingMonsters, destroyedBullets, newFires) = handleBulletCollisions(currentTime, monsters, Nil, Nil, Nil)

    for {
      p <- ZIO.foreach(fires)(e => e.update(currentTime, elapsedMillis))
      g = if (userInput.mouseButtons.contains(MouseButton.BUTTON_LEFT))
        gun
          .copy(particles = gun.particles.diff(destroyedBullets))
          .update(elapsedMillis)
          .fire(currentTime, camera.position, camera.front)
      else gun.copy(particles = gun.particles.diff(destroyedBullets)).update(elapsedMillis)
      c = nextCamera(userInput, elapsedMillis)
      f = flashLight.withDirection(c.front).withPosition(c.position)
    } yield copy(
      time = currentTime,
      monsters = survivingMonsters.map(_.animate),
      fires = p.flatten ++ newFires,
      gun = g,
      camera = c,
      flashLight = f,
      hud = hud.incFrames(currentTime)
    )
  }

  @tailrec
  private def handleBulletCollisions(
    time: Long,
    mons: List[GameItem],
    survivingMons: List[GameItem],
    destroyedBullets: List[Particle],
    newFires: List[Fire]
  ): (List[GameItem], List[Particle], List[Fire]) =
    mons match {
      case Nil => (survivingMons, destroyedBullets, newFires)
      case m :: ms =>
        gun.particles.find(p => m.aabbContains(p.item.position)) match {
          case Some(p) =>
            handleBulletCollisions(
              time,
              ms,
              survivingMons,
              p :: destroyedBullets,
              baseFire.duplicate(time, p.item.position) :: newFires
            )
          case None =>
            handleBulletCollisions(time, ms, m :: survivingMons, destroyedBullets, newFires)
        }
    }

  def nextCamera(userInput: UserInput, elapsedMillis: Long): Camera = {
    val keys           = userInput.keys
    val cursorMovement = userInput.cursorMovement

    // move forward/back
    val dz =
      if (keys.contains(Key.UP)) moveSpeed * elapsedMillis
      else if (keys.contains(Key.DOWN)) -moveSpeed * elapsedMillis
      else 0f

    // strafe
    val dx =
      if (keys.contains(Key.LEFT)) -moveSpeed * elapsedMillis
      else if (keys.contains(Key.RIGHT)) moveSpeed * elapsedMillis
      else 0f

    // still need to look up terrain for y-position...
    val nextCameraProvisional = camera
      .movePosition(dx, 0, dz)

    terrain
      .getHeight(nextCameraProvisional.position)
      .fold(camera)(y => nextCameraProvisional.withHeight(y + cameraHeight))
      .rotate(yawDelta = cursorMovement.x / mouseSensitivity, pitchDelta = -cursorMovement.y / mouseSensitivity)
  }
}