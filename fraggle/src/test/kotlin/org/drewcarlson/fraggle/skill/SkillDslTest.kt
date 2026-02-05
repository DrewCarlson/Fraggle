package org.drewcarlson.fraggle.skill

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillDslTest {

    @Nested
    inner class SkillCreation {

        @Test
        fun `skill creates skill with name`() {
            val s = skill("my_skill") {
                description = "Test skill"
                execute { SkillResult.Success("OK") }
            }

            assertEquals("my_skill", s.name)
        }

        @Test
        fun `skill creates skill with description`() {
            val s = skill("test") {
                description = "A helpful description"
                execute { SkillResult.Success("OK") }
            }

            assertEquals("A helpful description", s.description)
        }

        @Test
        fun `skill requires description`() {
            assertThrows<IllegalArgumentException> {
                skill("test") {
                    // Missing description
                    execute { SkillResult.Success("OK") }
                }
            }
        }

        @Test
        fun `skill requires executor`() {
            assertThrows<IllegalArgumentException> {
                skill("test") {
                    description = "Test"
                    // Missing execute block
                }
            }
        }

        @Test
        fun `skill with no parameters has empty parameter list`() {
            val s = skill("simple") {
                description = "Simple skill"
                execute { SkillResult.Success("OK") }
            }

            assertTrue(s.parameters.isEmpty())
        }
    }

    @Nested
    inner class ParameterCreation {

        @Test
        fun `creates string parameter`() {
            val s = skill("test") {
                description = "Test"
                parameter<String>("text") {
                    description = "A text param"
                    required = true
                }
                execute { SkillResult.Success("OK") }
            }

            assertEquals(1, s.parameters.size)
            val param = s.parameters[0]
            assertEquals("text", param.name)
            assertEquals("A text param", param.description)
            assertEquals(ParameterType.StringType, param.type)
            assertTrue(param.required)
        }

        @Test
        fun `creates integer parameter`() {
            val s = skill("test") {
                description = "Test"
                parameter<Int>("count") {
                    description = "A count"
                }
                execute { SkillResult.Success("OK") }
            }

            val param = s.parameters[0]
            assertEquals(ParameterType.IntType, param.type)
        }

        @Test
        fun `creates long parameter`() {
            val s = skill("test") {
                description = "Test"
                parameter<Long>("bignum") {
                    description = "A big number"
                }
                execute { SkillResult.Success("OK") }
            }

            val param = s.parameters[0]
            assertEquals(ParameterType.LongType, param.type)
        }

        @Test
        fun `creates double parameter`() {
            val s = skill("test") {
                description = "Test"
                parameter<Double>("decimal") {
                    description = "A decimal"
                }
                execute { SkillResult.Success("OK") }
            }

            val param = s.parameters[0]
            assertEquals(ParameterType.DoubleType, param.type)
        }

        @Test
        fun `creates boolean parameter`() {
            val s = skill("test") {
                description = "Test"
                parameter<Boolean>("flag") {
                    description = "A flag"
                }
                execute { SkillResult.Success("OK") }
            }

            val param = s.parameters[0]
            assertEquals(ParameterType.BooleanType, param.type)
        }

        @Test
        fun `parameter is optional by default`() {
            val s = skill("test") {
                description = "Test"
                parameter<String>("optional") {
                    description = "Optional param"
                    // required not set
                }
                execute { SkillResult.Success("OK") }
            }

            assertFalse(s.parameters[0].required)
        }

        @Test
        fun `parameter can have default value`() {
            val s = skill("test") {
                description = "Test"
                parameter<Int>("count") {
                    description = "Count with default"
                    default = 10
                }
                execute { SkillResult.Success("OK") }
            }

            assertEquals(10, s.parameters[0].default)
        }

        @Test
        fun `parameter can have validator`() {
            val s = skill("test") {
                description = "Test"
                parameter<String>("name") {
                    description = "Name must be non-empty"
                    validate { it.isNotBlank() }
                }
                execute { SkillResult.Success("OK") }
            }

            assertNotNull(s.parameters[0].validator)
        }

        @Test
        fun `multiple parameters preserve order`() {
            val s = skill("test") {
                description = "Test"
                parameter<String>("first") { description = "First" }
                parameter<Int>("second") { description = "Second" }
                parameter<Boolean>("third") { description = "Third" }
                execute { SkillResult.Success("OK") }
            }

            assertEquals(3, s.parameters.size)
            assertEquals("first", s.parameters[0].name)
            assertEquals("second", s.parameters[1].name)
            assertEquals("third", s.parameters[2].name)
        }
    }

    @Nested
    inner class SpecialParameterTypes {

        @Test
        fun `stringArrayParameter creates array type`() {
            val param = stringArrayParameter(
                name = "tags",
                description = "List of tags",
                required = true,
            )

            assertEquals("tags", param.name)
            assertEquals("List of tags", param.description)
            assertEquals(ParameterType.StringArrayType, param.type)
            assertTrue(param.required)
        }

        @Test
        fun `stringArrayParameter is optional by default`() {
            val param = stringArrayParameter(
                name = "tags",
                description = "List of tags",
            )

            assertFalse(param.required)
        }

        @Test
        fun `enumParameter creates enum type with values`() {
            val param = enumParameter(
                name = "color",
                description = "Choose a color",
                values = listOf("red", "green", "blue"),
                required = true,
            )

            assertEquals("color", param.name)
            assertEquals("Choose a color", param.description)
            assertTrue(param.required)

            val enumType = param.type as ParameterType.EnumType
            assertEquals(listOf("red", "green", "blue"), enumType.values)
        }

        @Test
        fun `enumParameter can have default value`() {
            val param = enumParameter(
                name = "size",
                description = "Size",
                values = listOf("small", "medium", "large"),
                default = "medium",
            )

            assertEquals("medium", param.default)
        }

        @Test
        fun `enumParameter has built-in validator`() {
            val param = enumParameter(
                name = "choice",
                description = "Choice",
                values = listOf("a", "b", "c"),
            )

            val validator = param.validator
            assertNotNull(validator)
            assertTrue(validator("a"))
            assertTrue(validator("b"))
            assertTrue(validator("c"))
            assertFalse(validator("d"))
            assertFalse(validator("invalid"))
        }
    }

    @Nested
    inner class TypeInference {

        @Test
        fun `inferParameterType infers String`() {
            assertEquals(ParameterType.StringType, inferParameterType<String>())
        }

        @Test
        fun `inferParameterType infers Int`() {
            assertEquals(ParameterType.IntType, inferParameterType<Int>())
        }

        @Test
        fun `inferParameterType infers Long`() {
            assertEquals(ParameterType.LongType, inferParameterType<Long>())
        }

        @Test
        fun `inferParameterType infers Double`() {
            assertEquals(ParameterType.DoubleType, inferParameterType<Double>())
        }

        @Test
        fun `inferParameterType infers Boolean`() {
            assertEquals(ParameterType.BooleanType, inferParameterType<Boolean>())
        }

        @Test
        fun `inferParameterType throws for unsupported types`() {
            assertThrows<IllegalArgumentException> {
                inferParameterType<List<String>>()
            }
        }
    }
}
