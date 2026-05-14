package applications

import hal.Clock
import hal.UART
import drivers.input.InputConstants
import drivers.input.KeyboardEvent
import drivers.input.InputService
import fdt.DeviceTree
import graphics.Framebuffer
import graphics.GraphicsService

object SnakeApplication {
    private const val BOARD_WIDTH = 32
    private const val BOARD_HEIGHT = 24
    private const val MAX_LENGTH = BOARD_WIDTH * BOARD_HEIGHT
    private const val TICK_MS: ULong = 115UL



    private const val COLOR_BACKGROUND: UInt = 0x00070b0eu
    private const val COLOR_BOARD: UInt = 0x00101812u
    private const val COLOR_BORDER: UInt = 0x004d6f55u
    private const val COLOR_FOOD: UInt = 0x00dc3f32u
    private const val COLOR_HEAD: UInt = 0x00b7f56au
    private const val COLOR_BODY: UInt = 0x0048b85au

    //private const val COLOR_BODY_ALT: UInt = 0x00369a4au
    private const val COLOR_BODY_ALT: UInt = COLOR_BODY
    private const val COLOR_GAME_OVER: UInt = 0x00882222u
    private var quitRequested = false

    fun run(deviceTree: DeviceTree?) {
        quitRequested = false
        if (!GraphicsService.initialize(deviceTree)) {
            UART.println("Graphics: ${GraphicsService.status()}")
            return
        }
        if (!InputService.initialize(deviceTree)) {
            UART.println("Input: ${InputService.status()}")
            return
        }

        val fb = GraphicsService.framebuffer()
        if (fb == null) {
            UART.println("Graphics: framebuffer unavailable")
            return
        }

        val cell = minOf(fb.mode.width / (BOARD_WIDTH.toUInt() + 2u), fb.mode.height / (BOARD_HEIGHT.toUInt() + 3u))
        if (cell == 0u) {
            UART.println("Snake: framebuffer too small")
            return
        }

        drainKeyboardEvents()
        drainSerialInput()

        val snakeX = IntArray(MAX_LENGTH)
        val snakeY = IntArray(MAX_LENGTH)
        var length = 5
        val startX = BOARD_WIDTH / 2
        val startY = BOARD_HEIGHT / 2
        var i = 0
        while (i < length) {
            snakeX[i] = startX - i
            snakeY[i] = startY
            i++
        }

        var direction = Direction.Right
        var seed = Clock.uptimeMillis().toUInt() xor 0x51f15eedu
        var foodX = 0
        var foodY = 0
        fun randomCell(): Int {
            seed = seed * 1664525u + 1013904223u
            return ((seed shr 16).toInt() and 0x7fff)
        }

        fun placeFood() {
            var attempts = 0
            while (attempts < 2048) {
                val x = randomCell() % BOARD_WIDTH
                val y = randomCell() % BOARD_HEIGHT
                var occupied = false
                var index = 0
                while (index < length) {
                    if (snakeX[index] == x && snakeY[index] == y) {
                        occupied = true
                        break
                    }
                    index++
                }
                if (!occupied) {
                    foodX = x
                    foodY = y
                    return
                }
                attempts++
            }
            foodX = 0
            foodY = 0
        }

        placeFood()
        render(fb, cell, snakeX, snakeY, length, foodX, foodY, gameOver = false)

        var score = 0
        var nextTick = Clock.uptimeMillis() + TICK_MS
        var gameOver = false
        while (!gameOver) {
            InputService.poll()
            if (InputService.isKeyDown(InputConstants.KEY_ESC) || InputService.isKeyDown(InputConstants.KEY_Q) || quitRequested) {
                UART.println("Snake exited. Score: $score")
                return
            }

            direction = requestedTurn(direction)

            val now = Clock.uptimeMillis()
            if (now < nextTick) continue
            nextTick = now + TICK_MS

            val nextX = snakeX[0] + direction.dx
            val nextY = snakeY[0] + direction.dy
            val grow = nextX == foodX && nextY == foodY
            if (nextX !in 0 until BOARD_WIDTH || nextY !in 0 until BOARD_HEIGHT) {
                gameOver = true
            } else {
                val collisionLimit = if (grow) length else length - 1
                var collisionIndex = 0
                while (collisionIndex < collisionLimit) {
                    if (snakeX[collisionIndex] == nextX && snakeY[collisionIndex] == nextY) {
                        gameOver = true
                        break
                    }
                    collisionIndex++
                }
            }

            if (!gameOver) {
                val newLength = if (grow && length < MAX_LENGTH) length + 1 else length
                var moveIndex = newLength - 1
                while (moveIndex > 0) {
                    snakeX[moveIndex] = snakeX[moveIndex - 1]
                    snakeY[moveIndex] = snakeY[moveIndex - 1]
                    moveIndex--
                }
                snakeX[0] = nextX
                snakeY[0] = nextY
                length = newLength
                if (grow) {
                    score++
                    placeFood()
                }
            }

            render(fb, cell, snakeX, snakeY, length, foodX, foodY, gameOver)
        }

        UART.println("Snake game over. Score: $score")
        val end = Clock.uptimeMillis() + 2_000UL
        while (Clock.uptimeMillis() < end) {
            InputService.poll()
            if (InputService.isKeyDown(InputConstants.KEY_ESC) || InputService.isKeyDown(InputConstants.KEY_Q)) break
        }
    }

    private fun requestedTurn(current: Direction): Direction {
        var direction = current
        while (true) {
            val event = InputService.nextKeyboardEvent() ?: break
            if (event.pressed) {
                if (event.code == InputConstants.KEY_ESC || event.code == InputConstants.KEY_Q) {
                    quitRequested = true
                } else {
                    direction = directionFromKeyboardEvent(event, direction)
                }
            }
        }
        while (true) {
            val c = UART.tryReadChar() ?: break
            direction = directionFromSerial(c, direction)
        }
        if ((InputService.isKeyDown(InputConstants.KEY_UP) || InputService.isKeyDown(InputConstants.KEY_W)) && direction != Direction.Down) direction =
            Direction.Up
        if ((InputService.isKeyDown(InputConstants.KEY_DOWN) || InputService.isKeyDown(InputConstants.KEY_S)) && direction != Direction.Up) direction =
            Direction.Down
        if ((InputService.isKeyDown(InputConstants.KEY_LEFT) || InputService.isKeyDown(InputConstants.KEY_A)) && direction != Direction.Right) direction =
            Direction.Left
        if ((InputService.isKeyDown(InputConstants.KEY_RIGHT) || InputService.isKeyDown(InputConstants.KEY_D)) && direction != Direction.Left) direction =
            Direction.Right
        return direction
    }

    private fun directionFromKeyboardEvent(event: KeyboardEvent, current: Direction): Direction =
        when (event.code) {
            InputConstants.KEY_UP, InputConstants.KEY_W -> if (current == Direction.Down) current else Direction.Up
            InputConstants.KEY_DOWN, InputConstants.KEY_S -> if (current == Direction.Up) current else Direction.Down
            InputConstants.KEY_LEFT, InputConstants.KEY_A -> if (current == Direction.Right) current else Direction.Left
            InputConstants.KEY_RIGHT, InputConstants.KEY_D -> if (current == Direction.Left) current else Direction.Right
            else -> current
        }

    private fun directionFromSerial(c: Char, current: Direction): Direction =
        when (c) {
            'w', 'W' -> if (current == Direction.Down) current else Direction.Up
            's', 'S' -> if (current == Direction.Up) current else Direction.Down
            'a', 'A' -> if (current == Direction.Right) current else Direction.Left
            'd', 'D' -> if (current == Direction.Left) current else Direction.Right
            'q', 'Q', '\u001b' -> {
                quitRequested = true
                current
            }

            else -> current
        }

    private fun render(
        fb: Framebuffer,
        cell: UInt,
        snakeX: IntArray,
        snakeY: IntArray,
        length: Int,
        foodX: Int,
        foodY: Int,
        gameOver: Boolean,
    ) {
        val boardWidthPx = BOARD_WIDTH.toUInt() * cell
        val boardHeightPx = BOARD_HEIGHT.toUInt() * cell
        val originX = (fb.mode.width - boardWidthPx) / 2u
        val originY = (fb.mode.height - boardHeightPx) / 2u
        val inset = maxOf(1u, cell / 8u)

        fb.clear(COLOR_BACKGROUND)
        fb.fillRect(
            originX - cell,
            originY - cell,
            boardWidthPx + cell * 2u,
            boardHeightPx + cell * 2u,
            if (gameOver) COLOR_GAME_OVER else COLOR_BORDER,
        )
        fb.fillRect(originX, originY, boardWidthPx, boardHeightPx, COLOR_BOARD)

        drawCell(fb, originX, originY, cell, foodX, foodY, COLOR_FOOD, inset)

        var index = length - 1
        while (index >= 0) {
            val color = when {
                gameOver && index == 0 -> COLOR_GAME_OVER
                index == 0 -> COLOR_HEAD
                index % 2 == 0 -> COLOR_BODY
                else -> COLOR_BODY_ALT
            }
            drawCell(fb, originX, originY, cell, snakeX[index], snakeY[index], color, inset)
            index--
        }

        fb.presentAll()
    }

    private fun drawCell(
        fb: Framebuffer,
        originX: UInt,
        originY: UInt,
        cell: UInt,
        x: Int,
        y: Int,
        color: UInt,
        inset: UInt
    ) {
        val px = originX + x.toUInt() * cell + inset
        val py = originY + y.toUInt() * cell + inset
        val size = maxOf(1u, cell - inset * 2u)
        fb.fillRect(px, py, size, size, color)
    }

    private fun drainKeyboardEvents() {
        while (InputService.nextKeyboardEvent() != null) {
            // Discard stale shell keystrokes before the game starts
        }
    }

    private fun drainSerialInput() {
        while (UART.tryReadChar() != null) {
            // Discard stale serial bytes before the game starts
        }
    }

    private enum class Direction(val dx: Int, val dy: Int) {
        Up(0, -1),
        Down(0, 1),
        Left(-1, 0),
        Right(1, 0),
    }
}
