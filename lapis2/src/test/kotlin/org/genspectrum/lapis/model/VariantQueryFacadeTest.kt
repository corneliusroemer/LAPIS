package org.genspectrum.lapis.model

import org.genspectrum.lapis.silo.And
import org.genspectrum.lapis.silo.Maybe
import org.genspectrum.lapis.silo.Not
import org.genspectrum.lapis.silo.NucleotideSymbolEquals
import org.genspectrum.lapis.silo.Or
import org.genspectrum.lapis.silo.PangoLineageEquals
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VariantQueryFacadeTest {
    private lateinit var underTest: VariantQueryFacade

    @BeforeEach
    fun setup() {
        underTest = VariantQueryFacade()
    }

    @Test
    fun `given a variant query with a single entry then map should return the corresponding SiloQuery`() {
        val variantQuery = "300G"

        val result = underTest.map(variantQuery)

        val expectedResult = NucleotideSymbolEquals(300, "G")
        MatcherAssert.assertThat(result, Matchers.equalTo(expectedResult))
    }

    @Test
    fun `given a variant variantQuery with an 'And' expression the map should return the corresponding SiloQuery`() {
        val variantQuery = "300G & 400"

        val result = underTest.map(variantQuery)

        val expectedResult = And(
            listOf(
                NucleotideSymbolEquals(300, "G"),
                NucleotideSymbolEquals(400, "-"),
            ),
        )
        MatcherAssert.assertThat(result, Matchers.equalTo(expectedResult))
    }

    @Test
    fun `given a variant variantQuery with two 'And' expression the map should return the corresponding SiloQuery`() {
        val variantQuery = "300G & 400- & 500B"

        val result = underTest.map(variantQuery)

        val expectedResult = And(
            listOf(
                And(
                    listOf(
                        NucleotideSymbolEquals(300, "G"),
                        NucleotideSymbolEquals(400, "-"),
                    ),
                ),
                NucleotideSymbolEquals(500, "B"),
            ),
        )
        MatcherAssert.assertThat(result, Matchers.equalTo(expectedResult))
    }

    @Test
    fun `given a variant variantQuery with a 'Not' expression the map should return the corresponding SiloQuery`() {
        val variantQuery = "!300G"

        val result = underTest.map(variantQuery)

        val expectedResult = Not(NucleotideSymbolEquals(300, "G"))
        MatcherAssert.assertThat(result, Matchers.equalTo(expectedResult))
    }

    @Test
    fun `given a variant variantQuery with an 'Or' expression the map should return the corresponding SiloQuery`() {
        val variantQuery = "300G | 400"

        val result = underTest.map(variantQuery)

        val expectedResult = Or(
            listOf(
                NucleotideSymbolEquals(300, "G"),
                NucleotideSymbolEquals(400, "-"),
            ),
        )
        MatcherAssert.assertThat(result, Matchers.equalTo(expectedResult))
    }

    @Test
    fun `given a variant variantQuery with an bracket expression the map should return the corresponding SiloQuery`() {
        val variantQuery = "300C & (400A | 500G)"

        val result = underTest.map(variantQuery)

        val expectedResult = And(
            listOf(
                NucleotideSymbolEquals(300, "C"),
                Or(
                    listOf(
                        NucleotideSymbolEquals(400, "A"),
                        NucleotideSymbolEquals(500, "G"),
                    ),
                ),
            ),
        )
        MatcherAssert.assertThat(result, Matchers.equalTo(expectedResult))
    }

    @Test
    fun `given a variant variantQuery with a 'Maybe' expression the map should return the corresponding SiloQuery`() {
        val variantQuery = "MAYBE(300G)"

        val result = underTest.map(variantQuery)

        val expectedResult = Maybe(NucleotideSymbolEquals(300, "G"))
        MatcherAssert.assertThat(result, Matchers.equalTo(expectedResult))
    }

    @Test
    fun `given a variant variantQuery with a 'Pangolineage' expression the map should return the corresponding SiloQuery`() {
        val variantQuery = "A.1.2.3"

        val result = underTest.map(variantQuery)

        val expectedResult = PangoLineageEquals("A.1.2.3", false)
        MatcherAssert.assertThat(result, Matchers.equalTo(expectedResult))
    }

    @Test
    fun `given a variant variantQuery with a 'Pangolineage' expression including sublineages the map should return the corresponding SiloQuery`() {
        val variantQuery = "A.1.2.3*"

        val result = underTest.map(variantQuery)

        val expectedResult = PangoLineageEquals("A.1.2.3", true)
        MatcherAssert.assertThat(result, Matchers.equalTo(expectedResult))
    }
}
