package dev.emortal.marathon.game

import dev.emortal.immortal.game.Game
import dev.emortal.immortal.game.GameOptions
import dev.emortal.immortal.game.Team
import dev.emortal.marathon.BlockPalette
import dev.emortal.marathon.MarathonExtension
import dev.emortal.marathon.animation.BlockAnimator
import dev.emortal.marathon.animation.PathAnimator
import dev.emortal.marathon.generator.Generator
import dev.emortal.marathon.generator.LegacyGenerator
import dev.emortal.marathon.utils.firsts
import dev.emortal.marathon.utils.sendBlockDamage
import dev.emortal.marathon.utils.setBlock
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.sound.SoundEvent
import net.minestom.server.timer.Task
import net.minestom.server.utils.time.TimeUnit
import world.cepi.kstom.Manager
import world.cepi.kstom.event.listenOnly
import world.cepi.kstom.util.asPos
import world.cepi.kstom.util.asVec
import world.cepi.kstom.util.playSound
import world.cepi.kstom.util.roundToBlock
import world.cepi.particle.Particle
import world.cepi.particle.ParticleType
import world.cepi.particle.data.Color
import world.cepi.particle.renderer.Renderer
import world.cepi.particle.showParticle
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*
import kotlin.math.pow

class MarathonGame(gameOptions: GameOptions) : Game(gameOptions) {

    companion object {
        val SPAWN_POINT = Pos(0.5, 150.0, 0.5)
        val DATE_FORMAT = SimpleDateFormat("mm:ss")
    }

    private val defaultTeam = registerTeam(Team("default"))

    private val animator: BlockAnimator = PathAnimator(this)
    private val generator: Generator = LegacyGenerator

    var previousHighscore: Pair<Int, Long> = Pair(0, 0)
    var highscore = 0

    var targetY = 150
    var targetX = 0

    var breakingTask: Task? = null
    var currentBreakingProgress = 0

    var actionBarTask: Task = Manager.scheduler.buildTask { updateActionBar() }
        .repeat(Duration.ofSeconds(1))
        .schedule()

    var finalBlockPos: Point = Pos(0.0, 149.0, 0.0)

    // Amount of blocks in front of the player
    val length = 4

    var lastBlockTimestamp = 0L
    var startTimestamp = -1L

    var score = 0
    var combo = 0

    var blockPalette = BlockPalette.DEFAULT
        set(value) {
            if (blockPalette == value) return

            for (block in blocks) {
                setBlock(block.first, value.blocks.random())
            }

            field = value
        }

    var blocks = mutableListOf<Pair<Point, Block>>()

    var player: Player? = null

    override fun playerJoin(player: Player) {
        player.respawnPoint = SPAWN_POINT
        this.player = player
        defaultTeam.add(player)
    }

    override fun playerLeave(player: Player) {
        destroy()
    }

    override fun registerEvents() {
        eventNode.listenOnly<PlayerMoveEvent> {
            if (player != this@MarathonGame.player) return@listenOnly

            if (newPosition.y() < (blocks.minOf { it.first.y() }) - 3) {
                reset()
                return@listenOnly
            }

            val posUnderPlayer = newPosition.sub(0.0, 1.0, 0.0).roundToBlock().asPos()
            val pos2UnderPlayer = newPosition.sub(0.0, 2.0, 0.0).roundToBlock().asPos()

            val blockPositions = blocks.firsts()

            var index = blockPositions.indexOf(pos2UnderPlayer)

            if (index == -1) index = blockPositions.indexOf(posUnderPlayer)
            if (index == -1 || index == 0) return@listenOnly

            generateNextBlock(index, true)
        }

        eventNode.listenOnly<PlayerChangeHeldSlotEvent> {
            //val palette: BlockPalette = blockPaletteStore.getPalette(slot) ?: return@listenOnly
            //blockPalette = palette
        }
    }

    override fun gameStarted() {
        previousHighscore = MarathonExtension.storage.getHighscoreAndTime(players.first().uuid) ?: Pair(0, 0)

        sendMessage(Component.text("$previousHighscore is your highscore"))

        startTimestamp = System.currentTimeMillis()
        Manager.scheduler.buildTask { reset() }.delay(Duration.ofMillis(200)).schedule() // Fixes blocks not appearing
    }

    override fun gameDestroyed() {
        breakingTask?.cancel()
        actionBarTask.cancel()

        blocks.forEach {
            setBlock(it.first, Block.AIR)
        }
    }

    fun reset() {
        getPlayers().forEach {
            player?.sendMessage("Playing with ${it.username}")
        }

        if (animator is PathAnimator) {
            animator.lastSandEntity = null
        }

        blocks.forEach {
            if (it.second != Block.DIAMOND_BLOCK) animator.destroyBlockAnimated(it.first, it.second)
        }
        blocks.clear()

        breakingTask?.cancel()
        breakingTask = null

        score = 0
        combo = 0
        finalBlockPos = Pos(0.0, 149.0, 0.0)

        blocks.add(Pair(finalBlockPos, Block.DIAMOND_BLOCK))

        players.forEach {
            it.velocity = Vec.ZERO
            it.teleport(SPAWN_POINT)
        }

        if (highscore > previousHighscore.first) {
            val secondsTaken = (System.currentTimeMillis() - startTimestamp) / 1000

            MarathonExtension.storage.setHighscore(players.first().uuid, highscore, secondsTaken)
            previousHighscore = Pair(highscore, secondsTaken)

            sendMessage(Component.text("New highscore $highscore in $secondsTaken"))
        }

        generateNextBlock(length, false)

        startTimestamp = -1
        updateActionBar()
    }

    fun generateNextBlock(times: Int, inGame: Boolean) {
        if (inGame) {
            if (startTimestamp == -1L) {
                actionBarTask = Manager.scheduler.buildTask { updateActionBar() }
                    .repeat(Duration.ofSeconds(1))
                    .schedule()
                startTimestamp = System.currentTimeMillis()
            }

            val powerResult = 2.0.pow(combo / 30.0)
            val maxTimeTaken = 1000L * times / powerResult

            if (System.currentTimeMillis() - lastBlockTimestamp < maxTimeTaken) combo += times else combo = 0

            val basePitch: Float = 1 + (combo - 1) * 0.05f

            score += times

            highscore = highscore.coerceAtLeast(score)

            showTitle(
                Title.title(
                    Component.empty(),
                    Component.text(score, NamedTextColor.GREEN, TextDecoration.BOLD),
                    Title.Times.of(Duration.ZERO, Duration.ofMillis(500), Duration.ofMillis(500))
                )
            )

            playSound(basePitch)
            createBreakingTask()
            updateActionBar()
        }
        repeat(times) { generateNextBlock() }
        lastBlockTimestamp = System.currentTimeMillis()
    }

    fun generateNextBlock() {
        if (blocks.size > length) {
            if (blocks[0].second != Block.DIAMOND_BLOCK) animator.destroyBlockAnimated(
                blocks[0].first,
                blocks[0].second
            )

            blocks.removeAt(0)
        }

        if (finalBlockPos.y() == 150.0) targetY =
            0 else if (finalBlockPos.y() < 135 || finalBlockPos.y() > 240) targetY = 150

        finalBlockPos = generator.getNextPosition(finalBlockPos, targetX, targetY, score)

        val newPaletteBlock = blockPalette.blocks.random()
        val newPaletteBlockPos = finalBlockPos

        val lastBlock = blocks.last()
        animator.setBlockAnimated(newPaletteBlockPos, newPaletteBlock, lastBlock.first, lastBlock.second)

        blocks.add(Pair(newPaletteBlockPos, newPaletteBlock))

        showParticle(
            Particle.particle(
                type = ParticleType.INSTANT_EFFECT,
                count = 1,
                data = Color(0f, 0f, 0f, 0f)
            ),
            Renderer.fixedRectangle(newPaletteBlockPos.asVec(), newPaletteBlockPos.asVec().add(1.0, 1.0, 1.0))
        )

        //player.sendParticle(ParticleUtils.particle(Particle.INSTANT_EFFECT, finalBlockPos.x(), finalBlockPos.y(), finalBlockPos.z(), 0.6f, 0.6f, 0.6f, 15))
    }

    private fun createBreakingTask() {
        currentBreakingProgress = 0

        breakingTask?.cancel()
        breakingTask = Manager.scheduler.buildTask {
            if (blocks.size < 1) return@buildTask

            val block = blocks[0]

            currentBreakingProgress += 2

            if (currentBreakingProgress > 8) {
                playSound(Sound.sound(SoundEvent.BLOCK_WOOD_BREAK, Sound.Source.BLOCK, 1f, 1f), block.first)
                //setBlock(block.first, Block.AIR)

                generateNextBlock()
                createBreakingTask()

                return@buildTask
            }

            playSound(Sound.sound(SoundEvent.BLOCK_WOOD_HIT, Sound.Source.BLOCK, 0.5f, 1f), block.first)
            sendBlockDamage(block.first, currentBreakingProgress.toByte())
        }.delay(1000, TimeUnit.MILLISECOND).repeat(500, TimeUnit.MILLISECOND).schedule()
    }

    private fun updateActionBar() {
        val millisTaken: Long = if (startTimestamp == -1L) 0 else System.currentTimeMillis() - startTimestamp
        val formattedTime: String = DATE_FORMAT.format(Date(millisTaken))
        //val secondsTaken = (millisTaken / 1000.0).coerceAtLeast(1.0)
        //val scorePerSecond = (score / secondsTaken * 100).roundToInt() / 100.0

        val message: Component = Component.text()
            .append(Component.text(score, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .append(Component.text(" points", NamedTextColor.DARK_PURPLE))
            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
            .append(Component.text(formattedTime, NamedTextColor.GRAY))
            .build()

        sendActionBar(message)
    }

    fun playSound(pitch: Float) {
        playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 3f, pitch), Sound.Emitter.self())
    }

    override fun instanceCreate(): Instance {
        return MarathonExtension.PARKOUR_INSTANCE
    }

}