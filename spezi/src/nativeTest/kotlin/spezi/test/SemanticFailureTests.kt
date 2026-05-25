package spezi.test

import kotlin.test.*

class SemanticFailureTests {

    @BeforeTest
    fun setup() = TestUtils.setup()

    @AfterTest
    fun tearDown() = TestUtils.cleanup()

    @Test
    fun `Fail on undefined variable`() {
        val code = """
            fn main() -> i32 {
                return unknown_var
            }
        """
        TestUtils.assertCompilationFails(code)
    }

    @Test
    fun `Fail on type mismatch in assignment`() {
        val code = """
            fn main() -> i32 {
                let mut x: i32 = 10
                x = true
                return 0
            }
        """
        TestUtils.assertCompilationFails(code)
    }

    @Test
    fun `Fail on assignment to immutable variable`() {
        val code = """
            fn main() -> i32 {
                let x: i32 = 10
                x = 11
                return 0
            }
        """
        TestUtils.assertCompilationFails(code)
    }

    @Test
    fun `Fail on duplicate variable in same scope`() {
        val code = """
            fn main() -> i32 {
                let x: i32 = 10
                let x: i32 = 11
                return 0
            }
        """
        TestUtils.assertCompilationFails(code)
    }

    @Test
    fun `Fail on use after moving string`() {
        val code = """
            extern fn println(value: string) -> void

            fn main() -> i32 {
                let message: string = "hello"
                println(message)
                println(message)
                return 0
            }
        """
        TestUtils.assertCompilationFails(code)
    }

    @Test
    fun `Fail on undefined struct in new`() {
        val code = """
            fn main() -> i32 {
                let v = new UnknownStruct()
                return 0
            }
        """
        TestUtils.assertCompilationFails(code)
    }
}
