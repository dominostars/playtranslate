package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.language.InflectedForm
import com.playtranslate.language.InflectionTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [InflectionChain]: the one line format every surface draws (surface,
 * " · ", tags joined with " « " through [InflectionTag.label]) and how a
 * lookup's deinflection chain and a token's tags compose into it.
 */
@RunWith(RobolectricTestRunner::class)
class InflectionChainTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `format joins the labels with guillemets after the surface`() {
        val form = InflectedForm(
            "飲んでいなかった",
            listOf(InflectionTag.TE, InflectionTag.IRU, InflectionTag.NEGATIVE, InflectionTag.TA),
        )
        val negative = ctx.getString(R.string.inflection_negative)
        assertEquals("飲んでいなかった · -て « -いる « $negative « -た", InflectionChain.format(ctx, form))
    }

    @Test
    fun `format uses each tag's label, localized names included`() {
        val form = InflectedForm("言わせて", listOf(InflectionTag.CAUSATIVE, InflectionTag.TE))
        assertEquals(
            "言わせて · " + InflectionTag.CAUSATIVE.label(ctx) + " « -て",
            InflectionChain.format(ctx, form),
        )
        assertEquals(ctx.getString(R.string.inflection_causative), InflectionTag.CAUSATIVE.label(ctx))
    }

    @Test
    fun `compose is null when neither chain has a step`() {
        assertNull(InflectionChain.compose("食べる", emptyList(), emptyList()))
    }

    @Test
    fun `compose puts the lookup's deinflection before the token's tags`() {
        val form = InflectionChain.compose(
            "弾けた",
            deinflection = listOf(InflectionTag.POTENTIAL),
            tokenTags = listOf(InflectionTag.TA),
        )
        assertEquals(InflectedForm("弾けた", listOf(InflectionTag.POTENTIAL, InflectionTag.TA)), form)
    }

    @Test
    fun `compose keeps either chain alone`() {
        assertEquals(
            InflectedForm("食べた", listOf(InflectionTag.TA)),
            InflectionChain.compose("食べた", emptyList(), listOf(InflectionTag.TA)),
        )
        assertEquals(
            InflectedForm("食べません", listOf(InflectionTag.MASU, InflectionTag.NEGATIVE)),
            InflectionChain.compose("食べません", listOf(InflectionTag.MASU, InflectionTag.NEGATIVE), emptyList()),
        )
    }
}
