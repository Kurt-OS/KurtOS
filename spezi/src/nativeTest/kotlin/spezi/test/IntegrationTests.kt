package spezi.test

import kotlin.test.*

class IntegrationTests {

    @BeforeTest
    fun setup() {
        TestUtils.setup()
    }

    @AfterTest
    fun tearDown() {
        TestUtils.cleanup()
    }

    @Test
    fun `Basic Arithmetic and Printing`() {
        val code = """
            extern fn printf(f: string, v: i32) -> void
            fn main() -> i32 {
                let res = 10 + 5 * 2
                printf("%d", res)
                return 0
            }
        """
        val output = TestUtils.compileAndReadBytecode(code)
        assertTrue(output.startsWith("KAPP2"))
        assertTrue(output.contains("bin "))
    }

    @Test
    fun `Functions`() {
        val code = """
            extern fn printf(f: string, v: i32) -> void
            fn magSq(x: i32, y: i32) -> i32 {
                return (x * x) + (y * y)
            }
            fn main() -> i32 {
                printf("%d", magSq(3, 4))
                return 0
            }
        """
        val output = TestUtils.compileAndReadBytecode(code)
        assertTrue(output.startsWith("KAPP2"))
        assertTrue(output.contains("func main"))
    }

    @Test
    fun `Logic and Control Flow`() {
        val code = """
            extern fn printf(f: string, v: i32) -> void
            fn main() -> i32 {
                if 10 == 10 {
                    printf("T", 0)
                }
                if 5 != 5 {
                    printf("F", 0)
                } else {
                    printf("E", 0)
                }
                return 0
            }
        """
        val output = TestUtils.compileAndReadBytecode(code)
        assertTrue(output.contains("jnz "))
    }

    @Test
    fun `Recursive Function`() {
        val code = """
            extern fn printf(f: string, v: i32) -> void
            fn fib(n: i32) -> i32 {
                if n == 0 { return 0 }
                if n == 1 { return 1 }
                return fib(n - 1) + fib(n - 2)
            }
            fn main() -> i32 {
                printf("%d", fib(6))
                return 0
            }
        """
        val output = TestUtils.compileAndReadBytecode(code)
        assertTrue(output.contains("call "))
    }

    @Test
    fun `Import Module`() {
        val mathLib = """
            fn square(n: i32) -> i32 { return n * n }
        """
        
        val mainCode = """
            extern fn printf(f: string, v: i32) -> void
            import lib.math
            fn main() -> i32 {
                printf("%d", square(5))
                return 0
            }
        """
        
        val output = TestUtils.compileAndReadBytecode(
            mainCode, 
            extraFiles = mapOf("lib/math.spz" to mathLib)
        )
        assertTrue(output.contains("func square"))
    }

    @Test
    fun `While Loop`() {
        val code = """
            fn main() -> i32 {
                let mut i = 0
                while i < 3 {
                    i = i + 1
                }
                return i
            }
        """
        val output = TestUtils.compileAndReadBytecode(code)
        assertTrue(output.contains("label "))
        assertTrue(output.contains("jmp "))
    }
}
