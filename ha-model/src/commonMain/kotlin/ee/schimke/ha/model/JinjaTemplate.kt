package ee.schimke.ha.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A deliberately small in-process evaluator for the subset of Home Assistant's Jinja2 that the
 * bundled dashboards actually use.
 *
 * `markdown` card templates are rendered here, locally, against the [HaSnapshot] — there is no
 * round-trip to HA's `render_template`. That keeps live, preview, demo and offline rendering
 * identical and avoids the WebSocket latency of a per-card server render. The trade-off: this is a
 * subset, not a full Jinja engine, so anything it can't evaluate makes [render] return `null` and
 * the caller falls back to a placeholder rather than showing wrong output.
 *
 * Supported surface (everything the demo dashboards exercise):
 * - statements: `{% set x = … %}`, `{% if … %}` / `{% elif … %}` / `{% else %}` / `{% endif %}`
 * - output `{{ … }}`, comments `{# … #}`
 * - literals: numbers, single/double-quoted strings, lists, `none`, `true`, `false`
 * - entity access: `states('id')`, `states.domain.object`, `states['id']`,
 *   `state_attr('id','attr')`, `is_state('id','x')`
 * - operators: `+ - * / //`, comparisons, `in` / `not in`, `is [not] number` / `is [not] none`,
 *   `and` / `or` / `not`, ternary `a if c else b`, attribute / index / slice access
 * - filters: `float`, `int`, `round`, `replace`, `title`, `count`, `list`, `selectattr`
 * - functions: `distance` (no home configured → none), `now`, `as_timestamp`
 *
 * Anything it can't evaluate makes [render] return `null`; callers then fall back to their
 * placeholder rather than showing wrong output.
 */
object JinjaTemplate {

  /**
   * Render [content] against [snapshot]. Returns the rendered string, or `null` if the template
   * uses constructs this evaluator doesn't support (so the caller can fall back).
   */
  fun render(content: String, snapshot: HaSnapshot, now: Instant? = null): String? =
    try {
      Renderer(snapshot, now ?: Clock.System.now()).renderTemplate(content)
    } catch (_: JinjaException) {
      null
    }

  private class JinjaException(message: String) : Exception(message)

  private fun fail(message: String): Nothing = throw JinjaException(message)

  // ---- Template structure -------------------------------------------------

  private sealed interface Node {
    data class Text(val text: String) : Node

    data class Output(val expr: Expr) : Node

    data class SetVar(val name: String, val expr: Expr) : Node

    data class If(val branches: List<Pair<Expr?, List<Node>>>) : Node
  }

  private class Renderer(val snapshot: HaSnapshot, val now: Instant) {
    private val scope = HashMap<String, Any?>()

    fun renderTemplate(content: String): String {
      val tokens = tokenizeTemplate(content)
      val nodes = parseNodes(tokens, mutableListOf(0))
      val sb = StringBuilder()
      render(nodes, sb)
      return sb.toString()
    }

    private fun render(nodes: List<Node>, sb: StringBuilder) {
      for (node in nodes) {
        when (node) {
          is Node.Text -> sb.append(node.text)
          is Node.Output -> sb.append(stringify(eval(node.expr)))
          is Node.SetVar -> scope[node.name] = eval(node.expr)
          is Node.If -> {
            for ((cond, body) in node.branches) {
              if (cond == null || truthy(eval(cond))) {
                render(body, sb)
                break
              }
            }
          }
        }
      }
    }

    // ---- Template tokenizer -----------------------------------------------

    /** Raw template token: literal text, an `{{ }}` output, or a `{% %}` statement. */
    private sealed interface RawToken {
      data class Text(val text: String) : RawToken

      data class Output(val src: String) : RawToken

      data class Stmt(val src: String) : RawToken
    }

    private fun tokenizeTemplate(content: String): List<RawToken> {
      val out = mutableListOf<RawToken>()
      var i = 0
      val text = StringBuilder()
      fun flush() {
        if (text.isNotEmpty()) {
          out += RawToken.Text(text.toString())
          text.clear()
        }
      }
      while (i < content.length) {
        if (content.startsWith("{{", i)) {
          val end = content.indexOf("}}", i + 2)
          if (end < 0) fail("unterminated {{")
          flush()
          out += RawToken.Output(content.substring(i + 2, end).trim())
          i = end + 2
        } else if (content.startsWith("{%", i)) {
          val end = content.indexOf("%}", i + 2)
          if (end < 0) fail("unterminated {%")
          flush()
          out += RawToken.Stmt(content.substring(i + 2, end).trim().trim('-').trim())
          i = end + 2
        } else if (content.startsWith("{#", i)) {
          val end = content.indexOf("#}", i + 2)
          if (end < 0) fail("unterminated {#")
          flush()
          i = end + 2
        } else {
          text.append(content[i])
          i++
        }
      }
      flush()
      return out
    }

    /** Parse the flat token list into a node tree; [cursor] is a 1-element mutable index. */
    private fun parseNodes(
      tokens: List<RawToken>,
      cursor: MutableList<Int>,
      stopWords: Set<String> = emptySet(),
    ): List<Node> {
      val nodes = mutableListOf<Node>()
      while (cursor[0] < tokens.size) {
        when (val tok = tokens[cursor[0]]) {
          is RawToken.Text -> {
            nodes += Node.Text(tok.text)
            cursor[0]++
          }
          is RawToken.Output -> {
            nodes += Node.Output(parseExpr(tok.src))
            cursor[0]++
          }
          is RawToken.Stmt -> {
            val keyword = tok.src.substringBefore(' ').substringBefore('\t')
            if (keyword in stopWords) return nodes
            when (keyword) {
              "set" -> {
                val body = tok.src.removePrefix("set").trim()
                val eq = body.indexOf('=')
                if (eq < 0) fail("malformed set")
                nodes +=
                  Node.SetVar(body.substring(0, eq).trim(), parseExpr(body.substring(eq + 1)))
                cursor[0]++
              }
              "if" -> nodes += parseIf(tokens, cursor)
              else -> fail("unsupported statement: $keyword")
            }
          }
        }
      }
      return nodes
    }

    private fun parseIf(tokens: List<RawToken>, cursor: MutableList<Int>): Node.If {
      val branches = mutableListOf<Pair<Expr?, List<Node>>>()
      var header = (tokens[cursor[0]] as RawToken.Stmt).src
      cursor[0]++
      val stop = setOf("elif", "else", "endif")
      while (true) {
        val keyword = header.substringBefore(' ')
        val cond: Expr? =
          when (keyword) {
            "if" -> parseExpr(header.removePrefix("if").trim())
            "elif" -> parseExpr(header.removePrefix("elif").trim())
            "else" -> null
            else -> fail("unexpected $keyword")
          }
        val body = parseNodes(tokens, cursor, stop)
        branches += cond to body
        val next = tokens.getOrNull(cursor[0]) as? RawToken.Stmt ?: fail("missing endif")
        val nextKw = next.src.substringBefore(' ')
        cursor[0]++
        if (nextKw == "endif") break
        header = next.src
      }
      return Node.If(branches)
    }

    // ---- Expression parser (Pratt) ----------------------------------------

    private fun parseExpr(src: String): Expr {
      val parser = ExprParser(lex(src))
      val expr = parser.parseTernary()
      parser.expectEnd()
      return expr
    }

    private inner class ExprParser(val toks: List<Tok>) {
      private var pos = 0

      private fun peek(): Tok = toks[pos]

      private fun next(): Tok = toks[pos++]

      // Operator/keyword matches must check the token TYPE, not just its
      // text — otherwise a string literal like '-' or 'or' is mistaken
      // for the operator of the same spelling.
      private fun peekOp(text: String): Boolean = peek().type == TokType.OP && peek().text == text

      private fun peekKw(text: String): Boolean = peek().type == TokType.NAME && peek().text == text

      private fun eatOp(text: String): Boolean {
        if (peekOp(text)) {
          pos++
          return true
        }
        return false
      }

      private fun eatKw(text: String): Boolean {
        if (peekKw(text)) {
          pos++
          return true
        }
        return false
      }

      private fun expectOp(text: String) {
        if (!eatOp(text)) fail("expected '$text' but got '${peek().text}'")
      }

      fun expectEnd() {
        if (peek().type != TokType.END) fail("trailing tokens: '${peek().text}'")
      }

      fun parseTernary(): Expr {
        val value = parseOr()
        if (peekKw("if")) {
          next()
          val cond = parseOr()
          if (!eatKw("else")) fail("expected 'else'")
          val alt = parseTernary()
          return Expr.Conditional(cond, value, alt)
        }
        return value
      }

      private fun parseOr(): Expr {
        var left = parseAnd()
        while (peekKw("or")) {
          next()
          left = Expr.Or(left, parseAnd())
        }
        return left
      }

      private fun parseAnd(): Expr {
        var left = parseNot()
        while (peekKw("and")) {
          next()
          left = Expr.And(left, parseNot())
        }
        return left
      }

      private fun parseNot(): Expr {
        if (peekKw("not")) {
          next()
          return Expr.Not(parseNot())
        }
        return parseComparison()
      }

      private fun parseComparison(): Expr {
        var left = parseAddSub()
        while (true) {
          when {
            peek().type == TokType.OP && peek().text in COMPARATORS -> {
              val op = next().text
              left = Expr.Compare(op, left, parseAddSub())
            }
            peekKw("in") -> {
              next()
              left = Expr.In(left, parseAddSub(), negate = false)
            }
            peekKw("not") &&
              toks.getOrNull(pos + 1)?.let { it.type == TokType.NAME && it.text == "in" } ==
                true -> {
              next()
              next()
              left = Expr.In(left, parseAddSub(), negate = true)
            }
            peekKw("is") -> {
              next()
              val negate = eatKw("not")
              val testName = next().text
              left = Expr.Is(left, testName, negate)
            }
            else -> return left
          }
        }
      }

      private fun parseAddSub(): Expr {
        var left = parseMulDiv()
        while (peekOp("+") || peekOp("-")) {
          val op = next().text
          left = Expr.Binary(op, left, parseMulDiv())
        }
        return left
      }

      private fun parseMulDiv(): Expr {
        var left = parseUnary()
        while (peekOp("*") || peekOp("/") || peekOp("//")) {
          val op = next().text
          left = Expr.Binary(op, left, parseUnary())
        }
        return left
      }

      private fun parseUnary(): Expr {
        if (peekOp("-")) {
          next()
          return Expr.Neg(parseUnary())
        }
        return parsePostfix()
      }

      private fun parsePostfix(): Expr {
        var e = parsePrimary()
        while (true) {
          when {
            peekOp(".") -> {
              next()
              e = Expr.Attr(e, next().text)
            }
            peekOp("[") -> {
              next()
              e = parseSubscript(e)
            }
            peekOp("(") -> {
              next()
              e = Expr.Call(e, parseArgs())
            }
            peekOp("|") -> {
              next()
              val name = next().text
              val args = if (eatOp("(")) parseArgs() else emptyList()
              e = Expr.FilterApply(e, name, args)
            }
            else -> return e
          }
        }
      }

      private fun parseSubscript(target: Expr): Expr {
        // `[a]`, `[a:b]`, `[:b]`, `[a:]`
        var lower: Expr? = null
        var upper: Expr? = null
        var isSlice = false
        if (!peekOp(":") && !peekOp("]")) lower = parseTernary()
        if (eatOp(":")) {
          isSlice = true
          if (!peekOp("]")) upper = parseTernary()
        }
        expectOp("]")
        return if (isSlice) Expr.Slice(target, lower, upper) else Expr.Index(target, lower!!)
      }

      private fun parseArgs(): List<Expr> {
        val args = mutableListOf<Expr>()
        if (!eatOp(")")) {
          do {
            args += parseTernary()
          } while (eatOp(","))
          expectOp(")")
        }
        return args
      }

      private fun parsePrimary(): Expr {
        val t = next()
        return when (t.type) {
          TokType.NUM ->
            if (t.text.contains('.')) Expr.Lit(t.text.toDouble()) else Expr.Lit(t.text.toLong())
          TokType.STR -> Expr.Lit(t.text)
          TokType.NAME ->
            when (t.text) {
              "none",
              "None" -> Expr.Lit(null)
              "true",
              "True" -> Expr.Lit(true)
              "false",
              "False" -> Expr.Lit(false)
              else -> Expr.Name(t.text)
            }
          TokType.OP ->
            when (t.text) {
              "(" -> {
                val e = parseTernary()
                expectOp(")")
                e
              }
              "[" -> {
                val items = mutableListOf<Expr>()
                if (!peekOp("]")) {
                  do {
                    items += parseTernary()
                  } while (eatOp(","))
                }
                expectOp("]")
                Expr.ListLit(items)
              }
              else -> fail("unexpected '${t.text}'")
            }
          TokType.END -> fail("unexpected end of expression")
        }
      }
    }

    // ---- Evaluation -------------------------------------------------------

    private fun eval(expr: Expr): Any? =
      when (expr) {
        is Expr.Lit -> expr.value
        is Expr.ListLit -> expr.items.map { eval(it) }
        is Expr.Name -> evalName(expr.name)
        is Expr.Neg -> -toNum(eval(expr.value))
        is Expr.Not -> !truthy(eval(expr.value))
        is Expr.And -> eval(expr.left).let { if (!truthy(it)) it else eval(expr.right) }
        is Expr.Or -> eval(expr.left).let { if (truthy(it)) it else eval(expr.right) }
        is Expr.Conditional ->
          if (truthy(eval(expr.cond))) eval(expr.then) else eval(expr.otherwise)
        is Expr.Binary -> evalBinary(expr.op, eval(expr.left), eval(expr.right))
        is Expr.Compare -> evalCompare(expr.op, eval(expr.left), eval(expr.right))
        is Expr.In -> evalIn(eval(expr.value), eval(expr.container)) != expr.negate
        is Expr.Is -> evalIs(eval(expr.value), expr.test) != expr.negate
        is Expr.Attr -> evalAttr(eval(expr.target), expr.name)
        is Expr.Index -> evalIndex(eval(expr.target), eval(expr.key))
        is Expr.Slice ->
          evalSlice(eval(expr.target), expr.lower?.let { eval(it) }, expr.upper?.let { eval(it) })
        is Expr.Call -> evalCall(expr.callee, expr.args.map { eval(it) })
        is Expr.FilterApply -> applyFilter(expr.name, eval(expr.target), expr.args.map { eval(it) })
      }

    private fun evalName(name: String): Any? =
      when {
        scope.containsKey(name) -> scope[name]
        name == "states" -> StatesProxy
        name in FUNCTIONS -> FunctionRef(name)
        else -> fail("unknown name: $name")
      }

    private fun evalBinary(op: String, l: Any?, r: Any?): Any? {
      if (op == "+" && (l is String || r is String)) return stringify(l) + stringify(r)
      val a = toNum(l)
      val b = toNum(r)
      return when (op) {
        "+" -> numResult(a + b, l, r)
        "-" -> numResult(a - b, l, r)
        "*" -> numResult(a * b, l, r)
        "/" -> a / b
        "//" -> kotlin.math.floor(a / b).toLong()
        else -> fail("bad op $op")
      }
    }

    /**
     * Keep integer arithmetic integral (Jinja prints `5`, not `5.0`); promote when either side is a
     * Double.
     */
    private fun numResult(value: Double, l: Any?, r: Any?): Any =
      if (l is Long && r is Long) value.toLong() else value

    private fun evalCompare(op: String, l: Any?, r: Any?): Boolean {
      if (op == "==" || op == "!=") {
        val eq = valueEquals(l, r)
        return if (op == "==") eq else !eq
      }
      val a = toNum(l)
      val b = toNum(r)
      return when (op) {
        "<" -> a < b
        "<=" -> a <= b
        ">" -> a > b
        ">=" -> a >= b
        else -> fail("bad comparison $op")
      }
    }

    private fun evalIn(value: Any?, container: Any?): Boolean =
      when (container) {
        is List<*> -> container.any { valueEquals(it, value) }
        is String -> container.contains(stringify(value))
        else -> false
      }

    private fun evalIs(value: Any?, test: String): Boolean =
      when (test) {
        "number" -> (value is Long || value is Double)
        "none" -> value == null
        "string" -> value is String
        "defined" -> value != null
        else -> fail("unknown test: $test")
      }

    private fun evalAttr(target: Any?, name: String): Any? =
      when (target) {
        StatesProxy -> DomainProxy(name)
        is DomainProxy -> stateObj("${target.domain}.$name")
        is StateObj ->
          when (name) {
            "state" -> target.state
            "entity_id" -> target.entityId
            "attributes" -> AttrsProxy(target.entityId)
            "name" -> target.state
            else -> fail("unknown state attr .$name")
          }
        is AttrsProxy -> target.get(name)
        else -> fail("cannot read .$name on $target")
      }

    private fun evalIndex(target: Any?, key: Any?): Any? =
      when (target) {
        StatesProxy -> stateObj(stringify(key))
        is AttrsProxy -> target.get(stringify(key))
        is List<*> -> {
          val i = toNum(key).toInt()
          target.getOrNull(if (i < 0) target.size + i else i)
        }
        is String -> {
          val i = toNum(key).toInt()
          target.getOrNull(if (i < 0) target.length + i else i)?.toString() ?: ""
        }
        else -> fail("cannot index $target")
      }

    private fun evalSlice(target: Any?, lower: Any?, upper: Any?): Any? {
      val s = stringify(target)
      val from = lower?.let { toNum(it).toInt() } ?: 0
      val to = upper?.let { toNum(it).toInt() } ?: s.length
      val a = (if (from < 0) s.length + from else from).coerceIn(0, s.length)
      val b = (if (to < 0) s.length + to else to).coerceIn(a, s.length)
      return s.substring(a, b)
    }

    private fun evalCall(callee: Expr, args: List<Any?>): Any? {
      val name =
        (callee as? Expr.Name)?.name
          ?: ((eval(callee) as? FunctionRef)?.name)
          ?: fail("not callable")
      return when (name) {
        "states" -> snapshot.states[stringify(args[0])]?.state ?: "unknown"
        "state_attr" -> attribute(stringify(args[0]), stringify(args[1]))
        "is_state" -> snapshot.states[stringify(args[0])]?.state == stringify(args[1])
        // No home location in the snapshot, so distance-from-home is unknown.
        "distance" -> null
        "now" -> now
        "as_timestamp" -> asTimestamp(args.getOrNull(0))
        "float" -> toFloatOr(args[0], args.getOrNull(1))
        "int" -> toIntOr(args[0], args.getOrNull(1))
        "round" -> roundValue(args[0], args.getOrNull(1), args.getOrNull(2))
        "replace" -> stringify(args[0]).replace(stringify(args[1]), stringify(args[2]))
        else -> fail("unknown function: $name")
      }
    }

    // ---- Filters ----------------------------------------------------------

    private fun applyFilter(name: String, value: Any?, args: List<Any?>): Any? =
      when (name) {
        "float" -> toFloatOr(value, args.getOrNull(0))
        "int" -> toIntOr(value, args.getOrNull(0))
        "round" -> roundValue(value, args.getOrNull(0), args.getOrNull(1))
        "replace" -> stringify(value).replace(stringify(args[0]), stringify(args[1]))
        "title" ->
          stringify(value).split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
          }
        "count",
        "length" ->
          when (value) {
            is List<*> -> value.size.toLong()
            is DomainProxy -> value.entities().size.toLong()
            is String -> value.length.toLong()
            else -> 0L
          }
        "list" -> asList(value)
        "selectattr" -> selectAttr(asList(value), args)
        else -> fail("unknown filter: $name")
      }

    private fun selectAttr(items: List<Any?>, args: List<Any?>): List<Any?> {
      val attr = stringify(args[0])
      val test = args.getOrNull(1)?.let { stringify(it) }
      val arg = args.getOrNull(2)
      return items.filter { item ->
        val v = evalAttr(item, attr)
        when (test) {
          null -> truthy(v)
          "eq",
          "equalto",
          "==" -> valueEquals(v, arg)
          "ne" -> !valueEquals(v, arg)
          "match" -> Regex(stringify(arg)).containsMatchIn(stringify(v))
          "search" -> Regex(stringify(arg)).containsMatchIn(stringify(v))
          else -> fail("unknown selectattr test: $test")
        }
      }
    }

    private fun asList(value: Any?): List<Any?> =
      when (value) {
        is List<*> -> value
        is DomainProxy -> value.entities()
        null -> emptyList()
        else -> listOf(value)
      }

    // ---- Entity helpers ---------------------------------------------------

    private fun stateObj(entityId: String): StateObj? =
      if (snapshot.states.containsKey(entityId)) StateObj(entityId) else null

    private fun attribute(entityId: String, attr: String): Any? {
      val json = snapshot.states[entityId]?.attributes ?: return null
      return jsonToValue(json[attr])
    }

    private inner class StateObj(val entityId: String) {
      val state: String
        get() = snapshot.states[entityId]?.state ?: "unknown"

      override fun toString(): String = state
    }

    private inner class AttrsProxy(val entityId: String) {
      fun get(name: String): Any? = attribute(entityId, name)
    }

    private inner class DomainProxy(val domain: String) {
      fun entities(): List<StateObj> =
        snapshot.states.keys
          .filter { it.substringBefore('.') == domain }
          .sorted()
          .map { StateObj(it) }
    }

    private fun asTimestamp(value: Any?): Any? =
      when (value) {
        is Instant -> value.epochSeconds.toDouble()
        is String -> runCatching { Instant.parse(value).epochSeconds.toDouble() }.getOrNull()
        else -> null
      }

    // ---- String / number coercions ----------------------------------------

    private fun stringify(value: Any?): String =
      when (value) {
        null -> ""
        is Boolean -> if (value) "True" else "False"
        is Double ->
          if (value == value.toLong().toDouble() && !value.isInfinite()) {
            "${value.toLong()}.0"
          } else {
            value.toString()
          }
        is List<*> -> value.joinToString(", ", "[", "]") { stringify(it) }
        else -> value.toString()
      }

    private fun truthy(value: Any?): Boolean =
      when (value) {
        null -> false
        is Boolean -> value
        is Long -> value != 0L
        is Double -> value != 0.0
        is String -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        is DomainProxy -> value.entities().isNotEmpty()
        else -> true
      }

    private fun toNum(value: Any?): Double =
      when (value) {
        is Long -> value.toDouble()
        is Double -> value
        is Boolean -> if (value) 1.0 else 0.0
        is String -> value.toDoubleOrNull() ?: fail("not a number: '$value'")
        else -> fail("not a number: $value")
      }

    private fun toFloatOr(value: Any?, default: Any?): Any {
      val d =
        when (value) {
          is Long -> value.toDouble()
          is Double -> value
          is String -> value.toDoubleOrNull()
          else -> null
        }
      return d ?: (default?.let { toNum(it) } ?: 0.0)
    }

    private fun toIntOr(value: Any?, default: Any?): Any {
      val l =
        when (value) {
          is Long -> value
          is Double -> value.toLong()
          is String -> value.toDoubleOrNull()?.toLong()
          else -> null
        }
      return l ?: (default?.let { toNum(it).toLong() } ?: 0L)
    }

    private fun roundValue(value: Any?, precisionArg: Any?, methodArg: Any?): Double {
      val v = toNum(value)
      val precision = precisionArg?.let { toNum(it).toInt() } ?: 0
      val factor = pow10(precision)
      val scaled = v * factor
      val rounded =
        when (methodArg?.let { stringify(it) }) {
          "floor" -> kotlin.math.floor(scaled)
          "ceil" -> kotlin.math.ceil(scaled)
          else -> kotlin.math.round(scaled)
        }
      return rounded / factor
    }

    private fun pow10(n: Int): Double {
      var r = 1.0
      repeat(kotlin.math.abs(n)) { r *= 10 }
      return if (n < 0) 1 / r else r
    }

    private fun valueEquals(a: Any?, b: Any?): Boolean {
      if (a == null || b == null) return a == b
      if ((a is Long || a is Double) && (b is Long || b is Double)) return toNum(a) == toNum(b)
      return stringify(a) == stringify(b)
    }
  }

  // ---- Expression AST -------------------------------------------------------

  private sealed interface Expr {
    data class Lit(val value: Any?) : Expr

    data class ListLit(val items: List<Expr>) : Expr

    data class Name(val name: String) : Expr

    data class Neg(val value: Expr) : Expr

    data class Not(val value: Expr) : Expr

    data class And(val left: Expr, val right: Expr) : Expr

    data class Or(val left: Expr, val right: Expr) : Expr

    data class Conditional(val cond: Expr, val then: Expr, val otherwise: Expr) : Expr

    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr

    data class Compare(val op: String, val left: Expr, val right: Expr) : Expr

    data class In(val value: Expr, val container: Expr, val negate: Boolean) : Expr

    data class Is(val value: Expr, val test: String, val negate: Boolean) : Expr

    data class Attr(val target: Expr, val name: String) : Expr

    data class Index(val target: Expr, val key: Expr) : Expr

    data class Slice(val target: Expr, val lower: Expr?, val upper: Expr?) : Expr

    data class Call(val callee: Expr, val args: List<Expr>) : Expr

    data class FilterApply(val target: Expr, val name: String, val args: List<Expr>) : Expr
  }

  /** Sentinel for `states` before an access tells us what's wanted. */
  private object StatesProxy

  private data class FunctionRef(val name: String)

  private val FUNCTIONS =
    setOf(
      "states",
      "state_attr",
      "is_state",
      "distance",
      "now",
      "as_timestamp",
      "float",
      "int",
      "round",
      "replace",
    )

  private val COMPARATORS = setOf("==", "!=", "<", "<=", ">", ">=")

  // ---- Expression lexer -----------------------------------------------------

  private enum class TokType {
    NUM,
    STR,
    NAME,
    OP,
    END,
  }

  private data class Tok(val type: TokType, val text: String)

  private fun lex(src: String): List<Tok> {
    val out = mutableListOf<Tok>()
    var i = 0
    while (i < src.length) {
      val c = src[i]
      when {
        c.isWhitespace() -> i++
        c == '\'' || c == '"' -> {
          val end = src.indexOf(c, i + 1)
          if (end < 0) fail("unterminated string")
          out += Tok(TokType.STR, src.substring(i + 1, end))
          i = end + 1
        }
        c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
          val start = i
          while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
          out += Tok(TokType.NUM, src.substring(start, i))
        }
        c.isLetter() || c == '_' -> {
          val start = i
          while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
          out += Tok(TokType.NAME, src.substring(start, i))
        }
        else -> {
          val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
          if (two in setOf("//", "==", "!=", "<=", ">=")) {
            out += Tok(TokType.OP, two)
            i += 2
          } else {
            out += Tok(TokType.OP, c.toString())
            i++
          }
        }
      }
    }
    out += Tok(TokType.END, "")
    return out
  }

  // ---- JSON attribute coercion ----------------------------------------------

  private fun jsonToValue(element: JsonElement?): Any? =
    when (element) {
      null -> null
      is JsonPrimitive ->
        when {
          element.isString -> element.content
          element.booleanOrNull != null -> element.booleanOrNull
          element.longOrNull != null -> element.longOrNull
          element.doubleOrNull != null -> element.doubleOrNull
          element.content == "null" -> null
          else -> element.content
        }
      is JsonArray -> element.map { jsonToValue(it) }
      is JsonObject -> element.mapValues { jsonToValue(it.value) }
    }
}
