package org.drewcarlson.fraggle.skill

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillRegistryTest {

    private fun createTestSkill(name: String, description: String = "Test skill"): Skill {
        return skill(name) {
            this.description = description
            execute { SkillResult.Success("OK") }
        }
    }

    @Nested
    inner class Registration {

        @Test
        fun `install adds skill to registry`() {
            val testSkill = createTestSkill("test_skill")
            val registry = SkillRegistry {
                install(testSkill)
            }

            assertTrue(registry.contains("test_skill"))
            assertEquals(testSkill, registry.get("test_skill"))
        }

        @Test
        fun `install multiple skills at once`() {
            val skill1 = createTestSkill("skill1")
            val skill2 = createTestSkill("skill2")
            val skill3 = createTestSkill("skill3")

            val registry = SkillRegistry {
                install(skill1, skill2, skill3)
            }

            assertEquals(3, registry.all().size)
            assertTrue(registry.contains("skill1"))
            assertTrue(registry.contains("skill2"))
            assertTrue(registry.contains("skill3"))
        }

        @Test
        fun `later installation overwrites earlier skill with same name`() {
            val skill1 = createTestSkill("duplicate", "First version")
            val skill2 = createTestSkill("duplicate", "Second version")

            val registry = SkillRegistry {
                install(skill1)
                install(skill2)
            }

            assertEquals(1, registry.all().size)
            assertEquals("Second version", registry.get("duplicate")?.description)
        }

        @Test
        fun `empty registry has no skills`() {
            val registry = SkillRegistry {}
            assertEquals(0, registry.all().size)
        }

        @Test
        fun `EmptySkillRegistry constant is empty`() {
            assertEquals(0, EmptySkillRegistry.all().size)
        }
    }

    @Nested
    inner class Lookup {

        @Test
        fun `get returns skill when found`() {
            val testSkill = createTestSkill("my_skill")
            val registry = SkillRegistry {
                install(testSkill)
            }

            val found = registry.get("my_skill")
            assertNotNull(found)
            assertEquals("my_skill", found.name)
        }

        @Test
        fun `get returns null when not found`() {
            val registry = SkillRegistry {}
            assertNull(registry.get("nonexistent"))
        }

        @Test
        fun `require returns skill when found`() {
            val testSkill = createTestSkill("required_skill")
            val registry = SkillRegistry {
                install(testSkill)
            }

            val found = registry.require("required_skill")
            assertEquals("required_skill", found.name)
        }

        @Test
        fun `require throws when not found`() {
            val registry = SkillRegistry {}

            val exception = assertThrows<IllegalArgumentException> {
                registry.require("nonexistent")
            }
            assertTrue(exception.message?.contains("nonexistent") == true)
        }

        @Test
        fun `contains returns true for registered skill`() {
            val registry = SkillRegistry {
                install(createTestSkill("exists"))
            }

            assertTrue(registry.contains("exists"))
        }

        @Test
        fun `contains returns false for unregistered skill`() {
            val registry = SkillRegistry {}
            assertFalse(registry.contains("nonexistent"))
        }

        @Test
        fun `all returns all registered skills`() {
            val skill1 = createTestSkill("alpha")
            val skill2 = createTestSkill("beta")

            val registry = SkillRegistry {
                install(skill1)
                install(skill2)
            }

            val all = registry.all()
            assertEquals(2, all.size)
            assertTrue(all.any { it.name == "alpha" })
            assertTrue(all.any { it.name == "beta" })
        }
    }

    @Nested
    inner class Groups {

        @Test
        fun `group creates skill group`() {
            val skill1 = createTestSkill("file_read")
            val skill2 = createTestSkill("file_write")

            val registry = SkillRegistry {
                group("filesystem", "File operations") {
                    install(skill1)
                    install(skill2)
                }
            }

            val group = registry.group("filesystem")
            assertNotNull(group)
            assertEquals("filesystem", group.name)
            assertEquals("File operations", group.description)
            assertEquals(2, group.skills.size)
        }

        @Test
        fun `group skills are also added to main registry`() {
            val skill = createTestSkill("grouped_skill")

            val registry = SkillRegistry {
                group("mygroup") {
                    install(skill)
                }
            }

            // Skill should be accessible from both group and main registry
            assertNotNull(registry.group("mygroup"))
            assertTrue(registry.contains("grouped_skill"))
        }

        @Test
        fun `allGroups returns all groups`() {
            val registry = SkillRegistry {
                group("group1") {
                    install(createTestSkill("s1"))
                }
                group("group2") {
                    install(createTestSkill("s2"))
                }
            }

            val groups = registry.allGroups()
            assertEquals(2, groups.size)
            assertTrue(groups.any { it.name == "group1" })
            assertTrue(groups.any { it.name == "group2" })
        }

        @Test
        fun `group returns null for nonexistent group`() {
            val registry = SkillRegistry {}
            assertNull(registry.group("nonexistent"))
        }
    }

    @Nested
    inner class OpenAIToolConversion {

        @Test
        fun `toOpenAITools converts all skills`() {
            val registry = SkillRegistry {
                install(createTestSkill("skill1"))
                install(createTestSkill("skill2"))
            }

            val tools = registry.toOpenAITools()

            assertEquals(2, tools.size)
            assertTrue(tools.all { it.type == "function" })
            assertTrue(tools.any { it.function.name == "skill1" })
            assertTrue(tools.any { it.function.name == "skill2" })
        }

        @Test
        fun `toOpenAITools with group name converts only group skills`() {
            val skill1 = createTestSkill("in_group")
            val skill2 = createTestSkill("outside_group")

            val registry = SkillRegistry {
                install(skill2)
                group("mygroup") {
                    install(skill1)
                }
            }

            val groupTools = registry.toOpenAITools("mygroup")

            assertEquals(1, groupTools.size)
            assertEquals("in_group", groupTools[0].function.name)
        }

        @Test
        fun `toOpenAITools with nonexistent group returns empty list`() {
            val registry = SkillRegistry {}
            val tools = registry.toOpenAITools("nonexistent")
            assertTrue(tools.isEmpty())
        }

        @Test
        fun `empty registry produces empty tools list`() {
            val registry = SkillRegistry {}
            assertTrue(registry.toOpenAITools().isEmpty())
        }
    }

    @Nested
    inner class Combination {

        @Test
        fun `plus combines two registries`() {
            val registry1 = SkillRegistry {
                install(createTestSkill("skill1"))
            }
            val registry2 = SkillRegistry {
                install(createTestSkill("skill2"))
            }

            val combined = registry1.plus(registry2)

            assertTrue(combined.contains("skill1"))
            assertTrue(combined.contains("skill2"))
            assertEquals(2, combined.all().size)
        }

        @Test
        fun `plus with single skill adds to registry`() {
            val registry = SkillRegistry {
                install(createTestSkill("existing"))
            }
            val newSkill = createTestSkill("new_skill")

            val combined = registry.plus(newSkill)

            assertTrue(combined.contains("existing"))
            assertTrue(combined.contains("new_skill"))
            assertEquals(2, combined.all().size)
        }

        @Test
        fun `plus preserves groups from both registries`() {
            val registry1 = SkillRegistry {
                group("group1") {
                    install(createTestSkill("s1"))
                }
            }
            val registry2 = SkillRegistry {
                group("group2") {
                    install(createTestSkill("s2"))
                }
            }

            val combined = registry1.plus(registry2)

            assertNotNull(combined.group("group1"))
            assertNotNull(combined.group("group2"))
        }

        @Test
        fun `plus second registry overwrites duplicate skills`() {
            val registry1 = SkillRegistry {
                install(createTestSkill("duplicate", "First"))
            }
            val registry2 = SkillRegistry {
                install(createTestSkill("duplicate", "Second"))
            }

            val combined = registry1.plus(registry2)

            assertEquals("Second", combined.get("duplicate")?.description)
        }
    }
}
