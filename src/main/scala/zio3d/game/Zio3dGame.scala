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
import zio3d.engine.loaders.assimp.static.loadStaticMesh
import zio3d.engine.loaders.particles.{loadFire, loadGun}
import zio3d.engine.loaders.terrain.loadTerrain
import zio3d.engine.shaders.particle.ParticleShaderProgram
import zio3d.engine.shaders.scene.SceneShaderProgram
import zio3d.engine.shaders.simple.SimpleShaderProgram
import zio3d.engine.shaders.skybox.SkyboxShaderProgram
import zio3d.game.GameResources.{fonts, models, textures}
import zio3d.game.config._
import zio3d.game.hud.{HudContext, HudState}

final case class Zio3dRenderer(
  simpleShaderProgram: SimpleShaderProgram,
  skyboxShaderProgram: SkyboxShaderProgram,
  sceneShaderProgram: SceneShaderProgram,
  particleShaderProgram: ParticleShaderProgram,
  hudContext: HudContext,
  perspective: Perspective
)

object Zio3dGame extends Game[Zio3dRenderer, Zio3dState] {

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
    } yield Zio3dRenderer(m, s, sc, p, h, config.perspective)

  override def initialState(r: Zio3dRenderer) =
    for {
      terrain <- loadTerrain(
                  r.sceneShaderProgram,
                  level.terrain.size,
                  level.terrain.scale,
                  level.terrain.minY,
                  level.terrain.maxY,
                  level.terrain.heightMap,
                  level.terrain.textureFile,
                  level.terrain.textInc
                )
      skybox       <- loadSkybox(r, level.sky)
      staticObjs   <- loadStaticObjects(r, level.staticObjects)
      monsters     <- loadMonsters(r, level.monsters, terrain)
      initPosition = terrain.getPosition(level.startPosition).getOrElse(Vector3.origin)

      fire <- loadFire(
               r.particleShaderProgram,
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
              r.particleShaderProgram,
              models.particle,
              SimpleMeshDefinition.image2D(textures.bullet, false),
              config.gun.maxBullets,
              config.gun.firingRateMillis,
              config.gun.bulletSpeed,
              config.gun.bulletTtl
            )

      now <- currentTime(TimeUnit.MILLISECONDS)
    } yield Zio3dState(
      now,
      terrain,
      skybox,
      monsters,
      List.empty,
      staticObjs,
      fire,
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

  private def loadSkybox(r: Zio3dRenderer, sky: SkyboxDefinition) =
    shaders.skybox
      .loadMesh(r.skyboxShaderProgram, sky)
      .map(Model.still)
      .map(GameItem(_).spawn(ItemInstance(Vector3.origin, sky.scale)))

  private def loadStaticObjects(r: Zio3dRenderer, staticObjects: List[GameObject]) =
    ZIO
      .foreach(staticObjects) { o =>
        for {
          i <- loadStaticMesh(o.model)
          m <- shaders.scene.loadMesh(r.sceneShaderProgram, i.head)
        } yield o.instances.map { i =>
          GameItem(Model.still(m))
            .spawn(
              ItemInstance(
                Vector3(i.position.x, 0, i.position.y),
                o.scale,
                o.rotation * AxisAngle4.y(i.orientation).quaternion
              )
            )
        }
      }
      .map(_.flatten)

  private def loadMonsters(r: Zio3dRenderer, monsters: List[GameObject], terrain: Terrain) =
    ZIO
      .foreach(monsters) { o =>
        for {
          a <- loadAnimMesh(o.model)
          m <- ZIO.foreach(a.meshes)(m => shaders.scene.loadMesh(r.sceneShaderProgram, m))
          i <- spawnInstances(o, m, a.animations, terrain)
        } yield a.animations.headOption.fold(GameItem(Model.still(m), i))(a => GameItem(Model.animated(m, a), i))
      }

  private def spawnInstances(obj: GameObject, meshes: List[Mesh], animations: List[Animation], terrain: Terrain) = {
    val numFrames = animations.headOption.map(_.frames.length)
    ZIO
      .foreach(obj.instances) { i =>
        nextInt.map { rand =>
          terrain.getPosition(i.position) map { pos =>
            ItemInstance(
              pos,
              obj.scale,
              obj.rotation * AxisAngle4.y(i.orientation).quaternion,
              obj.boxSize,
              numFrames.map(f => ModelAnimation(f, currentFrame = abs(rand) % f)),
              None
            )
          }
        }
      }
      .map(_.flatten)
  }

  override def render(windowSize: WindowSize, r: Zio3dRenderer, s: Zio3dState) = {
    val t = r.perspective.getTransformation(windowSize, s.camera)

    gl.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT) *>
      gl.clearColor(0.0f, 0.0f, 0.0f, 0.0f) *>
      ZIO.foreach(s.simpleItems)(i => shaders.simple.render(r.simpleShaderProgram, i, t, s.fixtures)) *>
      ZIO.foreach(s.sceneItems)(i => shaders.scene.render(r.sceneShaderProgram, i, t, s.fixtures)) *>
      ZIO.foreach(s.skyboxItems)(i => shaders.skybox.render(r.skyboxShaderProgram, i, t, s.fixtures)) *>
      ZIO.foreach(s.particles)(i => shaders.particle.render(r.particleShaderProgram, i, t, s.fixtures)) *>
      hud.render(r.hudContext, windowSize, s.hud) *>
      gl.enable(GL11.GL_DEPTH_TEST) *>
      gl.enable(GL11.GL_STENCIL_TEST)
  }

  override def nextState(s: Zio3dState, input: UserInput, currentTime: Long) =
    s.nextState(input, currentTime) map { n =>
      (n, !input.keys.contains(Key.ESC))
    }

  override def cleanup(r: Zio3dRenderer, s: Zio3dState) =
    cleanupState(s) *>
      shaders.simple.cleanup(r.simpleShaderProgram) *>
      shaders.scene.cleanup(r.sceneShaderProgram) *>
      shaders.skybox.cleanup(r.skyboxShaderProgram) *>
      shaders.particle.cleanup(r.particleShaderProgram)

  private def cleanupState(s: Zio3dState) =
    ZIO.foreach(s.simpleItems)(cleanupItem) *>
      ZIO.foreach(s.sceneItems)(cleanupItem) *>
      ZIO.foreach(s.skyboxItems)(cleanupItem) *>
      ZIO.foreach(s.particles)(cleanupItem)

  private def cleanupItem(i: GameItem) =
    ZIO.foreach(i.model.meshes) { m =>
      ZIO.foreach(m.material.texture)(gl.deleteTextures) *>
        ZIO.foreach(m.vbos)(gl.deleteBuffers) *>
        gl.deleteVertexArrays(m.vao)
    }
}
