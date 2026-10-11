package ee.schimke.ha.design

/**
 * The generation properties a card declares for its design: the small set of **discrete** values
 * that change the document its converter emits — a domain, a `device_class`, an icon name, an
 * enum-like state index. Their canonical encoding ([shape]) is the template part of a [DesignKey],
 * so each distinct combination indexes its own template, on the server and in the local library
 * alike.
 *
 * Only strings and enum ints belong here, never continuous values: a float such as `numeric_state`
 * would create a template per reading. Anything continuous, and any text the card merely shows, is
 * a named binding instead. Hosts that can't receive pushed bindings (launcher widgets) pick the
 * template by these properties and then write the remaining binding values into the document (spike
 * S1).
 */
class DesignProperties private constructor(val values: Map<String, String>) {

  /** Canonical encoding: `name=value` pairs sorted by name, joined with `;`. */
  val shape: String
    get() = values.entries.joinToString(";") { (k, v) -> "$k=$v" }

  operator fun get(name: String): String? = values[name]

  override fun equals(other: Any?): Boolean = other is DesignProperties && other.values == values

  override fun hashCode(): Int = values.hashCode()

  override fun toString(): String = "DesignProperties($shape)"

  /** Collects properties; only discrete kinds are offered. */
  class Builder internal constructor() {
    internal val values = sortedMapOf<String, String>()

    /** A string-valued property, such as a domain or an icon name. Null leaves it out. */
    fun string(name: String, value: String?) {
      if (value != null) values[checkName(name)] = checkValue(value)
    }

    /** An enum-like property, such as a state index. */
    fun enum(name: String, value: Int) {
      values[checkName(name)] = value.toString()
    }
  }

  companion object {
    fun build(block: Builder.() -> Unit): DesignProperties =
      DesignProperties(Builder().apply(block).values.toMap())

    /** Decode a [shape]. */
    fun parse(shape: String): DesignProperties =
      DesignProperties(
        shape
          .split(';')
          .filter { it.isNotEmpty() }
          .associate { part ->
            val i = part.indexOf('=')
            require(i > 0) { "not a property: $part" }
            part.substring(0, i) to part.substring(i + 1)
          }
          .toSortedMap()
      )

    private fun checkName(name: String): String = name.also {
      require(it.isNotEmpty() && it.none { c -> c == ';' || c == '=' }) { "bad name: $it" }
    }

    private fun checkValue(value: String): String = value.also {
      require(it.none { c -> c == ';' }) { "bad value: $it" }
    }
  }
}
