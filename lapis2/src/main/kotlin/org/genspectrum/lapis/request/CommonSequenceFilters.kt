package org.genspectrum.lapis.request

import com.fasterxml.jackson.core.ObjectCodec
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeType
import org.genspectrum.lapis.controller.AMINO_ACID_MUTATIONS_PROPERTY
import org.genspectrum.lapis.controller.LIMIT_PROPERTY
import org.genspectrum.lapis.controller.NUCLEOTIDE_MUTATIONS_PROPERTY
import org.genspectrum.lapis.controller.OFFSET_PROPERTY
import org.genspectrum.lapis.controller.ORDER_BY_PROPERTY
import org.genspectrum.lapis.controller.SPECIAL_REQUEST_PROPERTIES

interface CommonSequenceFilters {
    val sequenceFilters: Map<String, String>
    val nucleotideMutations: List<NucleotideMutation>
    val aaMutations: List<AminoAcidMutation>
    val orderByFields: List<OrderByField>
    val limit: Int?
    val offset: Int?

    fun isEmpty() = sequenceFilters.isEmpty() && nucleotideMutations.isEmpty() && aaMutations.isEmpty()
}

fun parseCommonFields(node: JsonNode, codec: ObjectCodec): ParsedCommonFields {
    val nucleotideMutations = when (val nucleotideMutationsNode = node.get(NUCLEOTIDE_MUTATIONS_PROPERTY)) {
        null -> emptyList()
        is ArrayNode -> nucleotideMutationsNode.map { codec.treeToValue(it, NucleotideMutation::class.java) }
        else -> throw IllegalArgumentException(
            "nucleotideMutations must be an array or null",
        )
    }

    val aminoAcidMutations = when (val aminoAcidMutationsNode = node.get(AMINO_ACID_MUTATIONS_PROPERTY)) {
        null -> emptyList()
        is ArrayNode -> aminoAcidMutationsNode.map { codec.treeToValue(it, AminoAcidMutation::class.java) }
        else -> throw IllegalArgumentException(
            "aminoAcidMutations must be an array or null",
        )
    }

    val orderByFields = when (val orderByNode = node.get(ORDER_BY_PROPERTY)) {
        null -> emptyList()
        is ArrayNode -> orderByNode.map { codec.treeToValue(it, OrderByField::class.java) }
        else -> throw IllegalArgumentException(
            "orderBy must be an array or null",
        )
    }

    val limitNode = node.get(LIMIT_PROPERTY)
    val limit = when (limitNode?.nodeType) {
        null -> null
        JsonNodeType.NULL, JsonNodeType.NUMBER -> limitNode.asInt()
        else -> throw IllegalArgumentException("limit must be a number or null")
    }

    val offsetNode = node.get(OFFSET_PROPERTY)
    val offset = when (offsetNode?.nodeType) {
        null -> null
        JsonNodeType.NULL, JsonNodeType.NUMBER -> offsetNode.asInt()
        else -> throw IllegalArgumentException("offset must be a number or null")
    }

    val sequenceFilters = node.fields()
        .asSequence()
        .filter { isStringOrNumber(it.value) }
        .filter { !SPECIAL_REQUEST_PROPERTIES.contains(it.key) }
        .associate { it.key to it.value.asText() }
    return ParsedCommonFields(nucleotideMutations, aminoAcidMutations, sequenceFilters, orderByFields, limit, offset)
}

data class ParsedCommonFields(
    val nucleotideMutations: List<NucleotideMutation>,
    val aminoAcidMutations: List<AminoAcidMutation>,
    val sequenceFilters: Map<String, String>,
    val orderByFields: List<OrderByField>,
    val limit: Int?,
    val offset: Int?,
)

private fun isStringOrNumber(jsonNode: JsonNode) =
    when (jsonNode.nodeType) {
        JsonNodeType.STRING,
        JsonNodeType.NUMBER,
        -> true

        else -> false
    }