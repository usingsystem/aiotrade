package org.aiotrade.lib.math.algebra

/**
 * Exception thrown when there is a cardinality mismatch in matrix or vector operations.
 * For example, vectors of differing cardinality cannot be added.
 */
class CardinalityException(expected: Int, cardinality: Int) extends IllegalArgumentException("Required cardinality " + expected + " but got " + cardinality)
