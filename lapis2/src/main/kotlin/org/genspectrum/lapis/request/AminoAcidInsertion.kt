package org.genspectrum.lapis.request

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import org.springframework.boot.jackson.JsonComponent
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

data class AminoAcidInsertion(val position: Int, val gene: String, val insertions: String) {
    companion object {
        fun fromString(aminoAcidInsertion: String): AminoAcidInsertion {
            val match = AMINO_ACID_INSERTION_REGEX.find(aminoAcidInsertion)
                ?: throw IllegalArgumentException("Invalid nucleotide mutation: $aminoAcidInsertion")

            val matchGroups = match.groups

            val position = matchGroups["position"]?.value?.toInt()
                ?: throw IllegalArgumentException(
                    "Invalid amino acid insertion: $aminoAcidInsertion: Did not find position",
                )

            val gene = matchGroups["gene"]?.value
                ?: throw IllegalArgumentException(
                    "Invalid amino acid insertion: $aminoAcidInsertion: Did not find gene",
                )

            val insertions = matchGroups["insertion"]?.value?.replace("?", ".*")
                ?: throw IllegalArgumentException(
                    "Invalid amino acid insertion: $aminoAcidInsertion: Did not find insertions",
                )

            return AminoAcidInsertion(
                position,
                gene,
                insertions,
            )
        }
    }
}

private val AMINO_ACID_INSERTION_REGEX =
    Regex(
        """^ins_(?<gene>[a-zA-Z0-9_-]+):(?<position>\d+):(?<insertion>[a-zA-Z0-9?_-]+)?$""",
    )

@JsonComponent
class AminoAcidInsertionDeserializer : JsonDeserializer<AminoAcidInsertion>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext) =
        AminoAcidInsertion.fromString(p.valueAsString)
}

@Component
class StringToAminoAcidInsertionConverter : Converter<String, AminoAcidInsertion> {
    override fun convert(source: String) = AminoAcidInsertion.fromString(source)
}
