package zio3d.game

import java.util.concurrent.TimeUnit

import org.lwjgl.opengl.GL11
import zio.ZIO
import zio.clock._
import zio.random._
import zio3d.core.gl
import zio3d.core.glfw.WindowSize
import zio3d.core.math._
import zio3d.engine._
import zio3d.engine.loaders.assimp.anim.loadAnimMesh
import zio3d.engine.loaders.assimp.loadStaticMesh
import zio3d.engine.loaders.particles.loadFire
import zio3d.engine.loaders.particles.loadGun
import zio3d.engine.loaders.terrain.loadTerrain
import zio3d.engine.shaders.particle.ParticleShaderInterpreter.ParticleShaderProgram
import zio3d.engine.shaders.scene.SceneShaderProgram
import zio3d.engine.shaders.simple.SimpleShaderInterpreter.SimpleShaderProgram
import zio3d.engine.shaders.skybox.SkyboxShaderInterpreter.SkyboxShaderProgram
import zio3d.game.GameResources.{fonts, models, textures}
import zio3d.game.config._
import zio3d.game.hud.HudRenderer.{HudContext, HudState}

final case class RenderContext(
  simpleShaderProgram: SimpleShaderProgram,
  skyboxShaderProgram: SkyboxShaderProgram,
  sceneShaderProgram: SceneShaderProgram,
  particleShaderProgram: ParticleShaderProgram,
  hudContext: HudContext,
  perspective: Perspective
)

final case class Perspective(
  fov: Float,
  zNear: Float,
  zFar: Float
) {

  def getTransformation(windowSize: WindowSize, camera: Camera): Transformation = {
    val aspectRatio = windowSize.width.toFloat / windowSize.height.toFloat
    Transformation(
      Matrix4.forPerspective(fov, aspectRatio, zNear, zFar),
      camera.viewMatrix
    )
  }
}

object Game extends GLApp[RenderContext, GameState] {

  final val config = GameConfig.live
  final val level  = config.level

  override def initRenderer =
    for {
      _  <- gl.createCapabilities
      _  <- gl.enable(GL11.GL_DEPTH_TEST)
      m  <- shaders.simple.loadShaderProgram
      s  <- shaders.skybox.loadShaderProgram
      sc <- shaders.scene.loadShaderProgram
      p  <- shaders.particle.loadShaderProgram
      h  <- hud.init(fonts.bold)
    } yield RenderContext(m, s, sc, p, h, config.perspective)

  override def initialState(c: RenderContext) =
    for {
      terrain <- loadTerrain(
                  c.sceneShaderProgram,
                  level.terrain.size,
                  level.terrain.scale,
                  level.terrain.minY,
                  level.terrain.maxY,
                  level.terrain.heightMap,
                  level.terrain.textureFile,
                  level.terrain.textInc
                )
      skybox       <- loadSkybox(c, level.sky)
      staticObjs   <- loadStaticObjects(c, level.staticObjects)
      monsters     <- loadMonsters(c, level.monsters, terrain)
      initPosition = terrain.getPosition(level.startPosition).getOrElse(Vector3.origin)

      fire <- loadFire(
               c.particleShaderProgram,
               models.particle,
               SimpleMeshDefinition.animatedImage2D(textures.fire.image, false, textures.fire.cols, textures.fire.rows),
               config.fire.maxParticles,
               config.fire.particleSpeed,
               config.fire.particleTtl,
               config.fire.particleCreationPeriodMillis,
               config.fire.particleUpdateTextureMillis,
               config.fire.randomRange,
               config.fire.randomRange,
               config.fire.randomRange,
               config.fire.animRange
             )
      gun <- loadGun(
              c.particleShaderProgram,
              models.particle,
              SimpleMeshDefinition.image2D(textures.bullet, false),
              config.gun.maxBullets,
              config.gun.firingRateMillis,
              config.gun.bulletSpeed,
              config.gun.bulletTtl
            )

      now <- currentTime(TimeUnit.MILLISECONDS)
    } yield GameState(
      now,
      terrain,
      skybox,
      monsters,
      List.empty,
      staticObjs,
      fire,
      List.empty,
      gun,
      Camera(initPosition, level.startFacing, 0),
      level.ambientLight,
      flashLight = makeSpotlight(initPosition),
      level.fog,
      HudState.initial(now)
    )

  private def makeSpotlight(initPosition: Vector3) =
    SpotLight(
      PointLight(Vector3(1, 1, 1), initPosition, 0.9f, Attenuation(0.0f, 0.0f, 0.01f)),
      Vector3(0, -1, 0),
      cos(toRadians(60.0f))
    )

  private def loadSkybox(c: RenderContext, sky: SkyboxDefinition) =
    shaders.skybox.loadMesh(c.skyboxShaderProgram, sky).map(GameItem(_).withScale(sky.scale))

  private def loadStaticObjects(c: RenderContext, staticObjects: List[GameObject]) =
    ZIO
      .foreach(staticObjects) { o =>
        for {
          i <- loadStaticMesh(o.model)
          m <- shaders.scene.loadMesh(c.sceneShaderProgram, i.head)
        } yield o.instances.map { i =>
          GameItem(m).withScale(o.scale).withRotation(o.rotation).withPosition(i.position.x, 0, i.position.y)
        }
      }
      .map(_.flatten)

  private def loadMonsters(c: RenderContext, monsters: List[GameObject], terrain: Terrain) =
    ZIO
      .foreach(monsters) { o =>
        for {
          a <- loadAnimMesh(o.model)
          m <- ZIO.foreach(a.meshes)(m => shaders.scene.loadMesh(c.sceneShaderProgram, m))
          i <- spawnInstances(o, m, a.animations, terrain)
        } yield i
      }
      .map(_.flatten)

  private def spawnInstances(obj: GameObject, meshes: List[Mesh], animations: List[Animation], terrain: Terrain) = {
    val anim      = animations.head
    val numFrames = anim.frames.length
    ZIO
      .foreach(obj.instances) { i =>
        nextInt.map { rand =>
          terrain.getPosition(i.position) map { pos =>
            GameItem(meshes, anim.copy(currentFrame = abs(rand) % numFrames))
              .withRotation(AxisAngle4(i.orientation, 0, 1, 0))
              .withScale(obj.scale)
              .withPosition(pos)
              .withBoxSize(obj.boxSize)
          }
        }
      }
      .map(_.flatten)
  }

  override def render(windowSize: WindowSize, c: RenderContext, s: GameState) = {
    val t = c.perspective.getTransformation(windowSize, s.camera)

    gl.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT) *>
      gl.clearColor(0.0f, 0.0f, 0.0f, 0.0f) *>
      shaders.simple.render(c.simpleShaderProgram, s.simpleItems, t, s.fixtures) *>
      shaders.scene.render(c.sceneShaderProgram, s.sceneItems, t, s.fixtures) *>
      shaders.skybox.render(c.skyboxShaderProgram, s.skyboxItems, t, s.fixtures) *>
      shaders.particle.render(c.particleShaderProgram, s.particles, t, s.fixtures) *>
      hud.render(c.hudContext, windowSize, s.hud) *>
      gl.enable(GL11.GL_DEPTH_TEST) *>
      gl.enable(GL11.GL_STENCIL_TEST)
  }

  override def nextState(s: GameState, input: UserInput, currentTime: Long) =
    s.nextState(input, currentTime) map { n =>
      (n, !input.keys.contains(Key.ESC))
    }
}