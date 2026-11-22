// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTest {
    @Test
    fun parsesAreaTask() {
        val task = TaskInfo.parse(
            """{"type":5,"type_name":"AAT","name":"Cévennes","editing":true,""" +
                """"valid":false,"errors":"No valid finish.\n","distance":37800.0,""" +
                """"types":[{"type":4,"name":"Racing"},{"type":5,"name":"AAT"}],""" +
                """"points":[{"waypoint_id":7,"name":"LFMT","kind":"start","type":2,""" +
                """"type_name":"Start cylinder","leg":0.0,"radius":1000.0,""" +
                """"types":[{"type":1,"name":"Start line"}]},""" +
                """{"waypoint_id":9,"name":"Col","kind":"turn","type":11,""" +
                """"type_name":"Area sector","leg":26100.0,"types":[],"future":1}],""" +
                """"distance_min":36700.0,"distance_max":38900.0,"aat_min_time":10800}""")

        assertEquals(5, task.type)
        assertEquals("Cévennes", task.name)
        assertTrue(task.editing)
        assertFalse(task.valid)
        assertTrue(task.isArea)
        assertEquals(10800.0, task.aatMinTime!!, 0.0)
        assertEquals(36700.0, task.distanceMin!!, 0.0)
        assertNull(task.active)
        assertEquals(listOf(TaskTypeChoice(4, "Racing"), TaskTypeChoice(5, "AAT")), task.types)
        assertEquals(2, task.points.size)
        assertEquals(1000.0, task.points[0].radius!!, 0.0)
        assertEquals(TaskTypeChoice(1, "Start line"), task.points[0].types.single())
        assertNull(task.points[1].radius)
        assertEquals(26100.0, task.points[1].leg, 0.0)
    }

    @Test
    fun parsesActiveTaskAndFiles() {
        val task = TaskInfo.parse(
            """{"type":4,"type_name":"Racing","name":"","editing":false,"valid":false,""" +
                """"errors":"","distance":0.0,"types":[],"points":[],"active":0}""")
        assertEquals(0, task.active)
        assertFalse(task.isArea)

        val files = TaskFileInfo.parseList(
            """[{"name":"300.tsk","path":"/x/tasks/300.tsk","index":0},""" +
                """{"name":"Comp day 2","path":"/x/comp.cup","index":1}]""")
        assertEquals(TaskFileInfo("Comp day 2", "/x/comp.cup", 1), files[1])
    }

    @Test
    fun opsMatchHeader() {
        val c = CoreHeader.constants("XCS_TASK_")
        for (op in TaskOp.entries)
            assertEquals(op.name, c["XCS_TASK_${op.name}"], op.code)
    }

    @Test
    fun rulesMatchHeader() {
        assertEquals(CoreHeader.constants("XCS_TASK_RULE_"),
                     TaskRule.entries.associate { "XCS_TASK_RULE_${it.name}" to it.code })
    }

    /** What xcs_task_get() says of zones, rules, other starts and targets. */
    @Test
    fun parsesZonesRulesAndTargets() {
        val task = TaskInfo.parse(
            """{"type":5,"type_name":"AAT","valid":true,"active":1,""" +
                """"points":[{"waypoint_id":1,"name":"A","kind":"start","type":2,""" +
                """"type_name":"Start cylinder","zone":{"shape":"cylinder","radius":1E3}},""" +
                """{"waypoint_id":2,"name":"B","kind":"turn","type":16,"type_name":"x",""" +
                """"zone":{"shape":"annular_sector","radius":2E4,"inner_radius":5E3,""" +
                """"start_radial":3E2,"end_radial":6E1},""" +
                """"target":{"range":3.5E-1,"radial":-2E1,"locked":true,"movable":true,""" +
                """"latitude":4.5E1,"longitude":5.5E0}}],""" +
                """"optional_starts":[{"waypoint_id":7,"name":"C"}],""" +
                """"rules":{"start_open":755,"start_close":-1,"start_max_height":1500,""" +
                """"start_height_ref":"msl","fai_allowed":false},""" +
                """"area":{"estimated_time":1.152E4,"time_delta":7.2E2}}""")
        val area = task.points[1]
        assertEquals(ZoneInfo("annular_sector", 20_000.0, 5000.0, 300.0, 60.0), area.zone)
        assertEquals(TargetInfo(0.35, -20.0, true, true, 45.0, 5.5), area.target)
        assertEquals(null, task.points[0].target)
        assertEquals(listOf(OptionalStart(7, "C")), task.optionalStarts)
        assertEquals(755, task.rules.startOpen)
        assertEquals(1500.0, task.rules.startMaxHeight, 0.0)
        assertEquals("msl", task.rules.startHeightRef)
        assertEquals(720.0, task.area?.timeDelta)
    }

    @Test
    fun soundOptionsMatchHeader() {
        val c = CoreHeader.constants("XCS_SOUND_")
        assertEquals(c, SoundOption.entries.associate { "XCS_SOUND_${it.name}" to it.code })
    }
}
