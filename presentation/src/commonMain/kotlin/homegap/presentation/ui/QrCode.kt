package homegap.presentation.ui

/**
 * A QR code for short text such as a URL: byte mode, error correction level M, versions 1 to 10
 * (up to 213 bytes). [isDark] reads a module; [size] is the side in modules, without the quiet zone.
 * It follows the QR Code standard (ISO/IEC 18004) directly, with a simplified mask choice.
 */
class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {
    fun isDark(x: Int, y: Int): Boolean = modules[y][x]

    companion object {
        /** Encodes [text], or returns null when it is too long for version 10. */
        fun encode(text: String): QrCode? {
            val data = text.encodeToByteArray()
            val version = (1..MAX_VERSION).firstOrNull { dataCapacityBits(it) >= headerBits(it) + data.size * 8 } ?: return null
            val codewords = withErrorCorrection(dataCodewords(data, version), version)
            return Builder(version).build(codewords)
        }

        private const val MAX_VERSION = 10

        /** Level M, versions 1 to 10: error correction codewords per block, and the number of blocks. */
        private val ECC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

        private fun headerBits(version: Int): Int = 4 + if (version <= 9) 8 else 16

        private fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val alignments = version / 7 + 2
                result -= (25 * alignments - 10) * alignments - 55
                if (version >= 7) {
                    result -= 36
                }
            }
            return result
        }

        private fun dataCodewordCount(version: Int): Int = rawModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version]

        private fun dataCapacityBits(version: Int): Int = dataCodewordCount(version) * 8

        /** Mode, length and the bytes, then the terminator and padding. */
        private fun dataCodewords(data: ByteArray, version: Int): IntArray {
            val bits = BitBuffer()
            bits.append(value = 0b0100, length = 4)
            bits.append(value = data.size, length = if (version <= 9) 8 else 16)
            data.forEach { bits.append(value = it.toInt() and 0xff, length = 8) }
            val capacity = dataCapacityBits(version)
            bits.append(value = 0, length = minOf(4, capacity - bits.size))
            bits.append(value = 0, length = (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) {
                bits.append(value = pad, length = 8)
                pad = pad xor 0xEC xor 0x11
            }
            return IntArray(bits.size / 8) { index -> bits.byteAt(index) }
        }

        /** Splits [data] into blocks, adds Reed-Solomon codewords to each, and interleaves them. */
        private fun withErrorCorrection(data: IntArray, version: Int): IntArray {
            val blockCount = BLOCKS[version]
            val eccLength = ECC_PER_BLOCK[version]
            val rawCodewords = rawModules(version) / 8
            val shortBlocks = blockCount - rawCodewords % blockCount
            val shortLength = rawCodewords / blockCount
            val divisor = reedSolomonDivisor(eccLength)
            val blocks = mutableListOf<IntArray>()
            var offset = 0
            for (index in 0 until blockCount) {
                val length = shortLength - eccLength + if (index < shortBlocks) 0 else 1
                val block = data.copyOfRange(offset, offset + length)
                offset += length
                val ecc = reedSolomonRemainder(block, divisor)
                // Short blocks get a placeholder so every block has the same length.
                val padded = if (index < shortBlocks) block + 0 else block
                blocks += padded + ecc
            }
            val result = mutableListOf<Int>()
            for (position in blocks[0].indices) {
                blocks.forEachIndexed { index, block ->
                    if (position != shortLength - eccLength || index >= shortBlocks) {
                        result += block[position]
                    }
                }
            }
            return result.toIntArray()
        }

        private fun reedSolomonDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = multiply(result[j], root)
                    if (j + 1 < degree) {
                        result[j] = result[j] xor result[j + 1]
                    }
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (byte in data) {
                val factor = byte xor result[0]
                for (i in 0 until result.size - 1) {
                    result[i] = result[i + 1]
                }
                result[result.size - 1] = 0
                for (i in result.indices) {
                    result[i] = result[i] xor multiply(divisor[i], factor)
                }
            }
            return result
        }

        /** Multiplication in GF(2^8) with the QR polynomial 0x11D. */
        private fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xff
        }
    }

    /** Lays out one symbol: function patterns, then the codewords, then the best mask. */
    private class Builder(private val version: Int) {
        private val size = version * 4 + 17
        private val modules = Array(size) { BooleanArray(size) }
        private val function = Array(size) { BooleanArray(size) }

        fun build(codewords: IntArray): QrCode {
            drawFunctionPatterns()
            drawCodewords(codewords)
            val mask = (0..7).minBy { mask ->
                applyMask(mask)
                drawFormatBits(mask)
                penalty().also { applyMask(mask) }
            }
            applyMask(mask)
            drawFormatBits(mask)
            return QrCode(size, modules)
        }

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            function[y][x] = true
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val positions = alignmentPositions()
            val last = positions.size - 1
            for (i in positions.indices) {
                for (j in positions.indices) {
                    val corner = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)
                    if (!corner) {
                        drawAlignment(positions[i], positions[j])
                    }
                }
            }
            drawFormatBits(0)
            drawVersion()
        }

        private fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        set(x, y, distance != 2 && distance != 4)
                    }
                }
            }
        }

        private fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) {
                    set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                }
            }
        }

        private fun alignmentPositions(): List<Int> {
            if (version == 1) return emptyList()
            val count = version / 7 + 2
            val step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            return listOf(6) + (1 until count).map { i -> size - 7 - (count - 1 - i) * step }
        }

        private fun drawFormatBits(mask: Int) {
            // Level M is 00 in the format information.
            val data = mask
            var remainder = data
            repeat(10) {
                remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537)
            }
            val bits = ((data shl 10) or remainder) xor 0x5412
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var remainder = version
            repeat(12) {
                remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25)
            }
            val bits = (version shl 12) or remainder
            for (i in 0 until 18) {
                val dark = ((bits ushr i) and 1) != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark)
                set(b, a, dark)
            }
        }

        /** Places the codewords in the zigzag order, two columns at a time, skipping function modules. */
        private fun drawCodewords(codewords: IntArray) {
            var index = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) {
                    right = 5
                }
                for (vertical in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vertical else vertical
                        if (!function[y][x] && index < codewords.size * 8) {
                            modules[y][x] = ((codewords[index ushr 3] ushr (7 - (index and 7))) and 1) != 0
                            index++
                        }
                    }
                }
                right -= 2
            }
        }

        /** XORs the data modules with [mask]; applying it twice undoes it. */
        private fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (invert && !function[y][x]) {
                        modules[y][x] = !modules[y][x]
                    }
                }
            }
        }

        /** Long runs, 2 × 2 blocks and an uneven dark share make a symbol harder to scan. */
        private fun penalty(): Int {
            var result = 0
            for (line in 0 until size) {
                var rowRun = 1
                var columnRun = 1
                for (i in 1 until size) {
                    if (modules[line][i] == modules[line][i - 1]) rowRun++ else rowRun = 1
                    if (rowRun == 5) result += 3 else if (rowRun > 5) result++
                    if (modules[i][line] == modules[i - 1][line]) columnRun++ else columnRun = 1
                    if (columnRun == 5) result += 3 else if (columnRun > 5) result++
                }
            }
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val color = modules[y][x]
                    if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) {
                        result += 3
                    }
                }
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            result += (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total * 10
            return result
        }
    }

    private class BitBuffer {
        private val bits = mutableListOf<Boolean>()
        val size: Int get() = bits.size

        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                bits += ((value ushr i) and 1) != 0
            }
        }

        fun byteAt(index: Int): Int {
            var byte = 0
            for (i in 0 until 8) {
                byte = (byte shl 1) or if (bits[index * 8 + i]) 1 else 0
            }
            return byte
        }
    }
}
