package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.dashboard.CardField
import ee.schimke.ha.client.dashboard.CardSettings
import ee.schimke.ha.client.dashboard.DashboardDraft
import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val settingsJson = Json { prettyPrint = true }

/**
 * A card's settings: a **Form** of the settings its type uses most ([CardSettings.fields]), and its
 * Lovelace **JSON** for anything else. Both edit one working copy of the card; a type the form
 * doesn't know opens on JSON. Apply needs every number to be a number and the JSON (when it is the
 * tab shown) to be one object with a `type`.
 */
@Composable
internal fun CardSettingsDialog(
  card: JsonObject,
  entities: Collection<EntityState>,
  onDismiss: () -> Unit,
  onSave: (JsonObject) -> Unit,
) {
  val fields = remember(card) { CardSettings.fields(card) }
  var working by remember(card) { mutableStateOf(card) }
  var json by remember(card) { mutableStateOf(false) }
  var text by
    remember(card) {
      mutableStateOf(
        if (fields.isEmpty()) settingsJson.encodeToString(JsonObject.serializer(), card) else ""
      )
    }
  // Number fields keep their text while it isn't a number yet; the card keeps the last valid one.
  val numberText = remember(card) { mutableStateMapOf<String, String>() }
  val parsed = remember(text) { parseCardSettings(text) }
  val showJson = json || fields.isEmpty()
  val numbersValid = numberText.values.all(CardSettings::isNumber)

  fun openJson() {
    text = settingsJson.encodeToString(JsonObject.serializer(), working)
    json = true
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Card settings") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fields.isNotEmpty()) {
          PrimaryTabRow(selectedTabIndex = if (showJson) 1 else 0) {
            Tab(
              selected = !showJson,
              enabled = parsed.isSuccess || !json,
              onClick = {
                parsed.onSuccess { working = it }
                numberText.clear()
                json = false
              },
              text = { Text("Form") },
            )
            Tab(
              selected = showJson,
              enabled = numbersValid,
              onClick = { if (!json) openJson() },
              text = { Text("JSON") },
            )
          }
        }
        if (showJson) {
          OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            isError = parsed.isFailure,
            supportingText = { parsed.exceptionOrNull()?.let { Text(it.message.orEmpty()) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
          )
        } else {
          CardSettingsForm(
            card = working,
            fields = fields,
            entities = entities,
            numberText = numberText,
            onChange = { working = it },
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = if (showJson) parsed.isSuccess else numbersValid,
        onClick = { if (showJson) parsed.onSuccess(onSave) else onSave(working) },
      ) {
        Text("Apply")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** [fields] of [card], each edit handed to [onChange] as the whole card. */
@Composable
private fun CardSettingsForm(
  card: JsonObject,
  fields: List<CardField>,
  entities: Collection<EntityState>,
  numberText: MutableMap<String, String>,
  onChange: (JsonObject) -> Unit,
) {
  Column(
    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    fields.forEach { field ->
      when (field) {
        is CardField.Text ->
          OutlinedTextField(
            value = CardSettings.text(card, field.key),
            onValueChange = { onChange(CardSettings.withText(card, field.key, it)) },
            label = { Text(field.label) },
            singleLine = !field.multiline,
            minLines = if (field.multiline) 3 else 1,
            modifier = Modifier.fillMaxWidth(),
          )
        is CardField.Number -> {
          val value = numberText[field.key] ?: CardSettings.number(card, field.key)
          val valid = CardSettings.isNumber(value)
          OutlinedTextField(
            value = value,
            onValueChange = {
              numberText[field.key] = it
              if (CardSettings.isNumber(it)) onChange(CardSettings.withNumber(card, field.key, it))
            },
            label = { Text(field.label) },
            singleLine = true,
            isError = !valid,
            supportingText = if (valid) null else ({ Text("Not a number.") }),
            modifier = Modifier.fillMaxWidth(),
          )
        }
        is CardField.Toggle ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, Modifier.weight(1f))
            Switch(
              checked = CardSettings.toggle(card, field),
              onCheckedChange = { onChange(CardSettings.withToggle(card, field, it)) },
              modifier = Modifier.semantics { contentDescription = field.label },
            )
          }
        is CardField.Choice ->
          ChoiceRow(
            label = field.label,
            value = CardSettings.text(card, field.key),
            options = CardSettings.choices(card, field),
            onChoose = { onChange(CardSettings.withText(card, field.key, it)) },
          )
        is CardField.TapAction ->
          ChoiceRow(
            label = field.label,
            value = CardSettings.tapAction(card),
            options = CardSettings.tapActions(card),
            onChoose = { onChange(CardSettings.withTapAction(card, it)) },
          )
        is CardField.Entity ->
          EntityRow(
            label = field.label,
            entityId = CardSettings.text(card, "entity"),
            entities = entities,
            onPick = { onChange(DashboardDraft.withEntity(card, it)) },
          )
        is CardField.Entities -> EntitiesRows(card, entities, onChange)
      }
    }
  }
}

/** A setting chosen from [options] (value to label): the current one's label opens the menu. */
@Composable
private fun ChoiceRow(
  label: String,
  value: String,
  options: List<Pair<String, String>>,
  onChoose: (String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f))
    Box {
      OutlinedButton(onClick = { open = true }) {
        Text(options.firstOrNull { it.first == value }?.second ?: value)
      }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        options.forEach { (option, optionLabel) ->
          DropdownMenuItem(
            text = { Text(optionLabel) },
            onClick = {
              open = false
              onChoose(option)
            },
          )
        }
      }
    }
  }
}

/** The card's one entity, changed by picking another. */
@Composable
private fun EntityRow(
  label: String,
  entityId: String,
  entities: Collection<EntityState>,
  onPick: (String) -> Unit,
) {
  var picking by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.labelMedium)
      Text(entityId.ifEmpty { "None" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    TextButton(onClick = { picking = true }) { Text("Change") }
  }
  if (picking) {
    EntityPickerDialog(
      entities = entities,
      onDismiss = { picking = false },
      onPick = {
        picking = false
        onPick(it)
      },
    )
  }
}

/** The card's `entities` rows: reorder, remove, add. */
@Composable
private fun EntitiesRows(
  card: JsonObject,
  entities: Collection<EntityState>,
  onChange: (JsonObject) -> Unit,
) {
  var adding by remember { mutableStateOf(false) }
  val ids = CardSettings.entityIds(card)
  Text("Entities", style = MaterialTheme.typography.labelMedium)
  if (ids.isEmpty()) Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
  ids.forEachIndexed { i, id ->
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(id, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
      IconButton(
        enabled = i > 0,
        onClick = { onChange(CardSettings.withEntityMoved(card, i, -1)) },
      ) {
        Icon(Icons.Filled.ArrowUpward, contentDescription = "Move $id up")
      }
      IconButton(
        enabled = i < ids.lastIndex,
        onClick = { onChange(CardSettings.withEntityMoved(card, i, 1)) },
      ) {
        Icon(Icons.Filled.ArrowDownward, contentDescription = "Move $id down")
      }
      IconButton(onClick = { onChange(CardSettings.withEntityRemoved(card, i)) }) {
        Icon(Icons.Filled.Delete, contentDescription = "Remove $id")
      }
    }
  }
  TextButton(onClick = { adding = true }) { Text("Add entity") }
  if (adding) {
    EntityPickerDialog(
      entities = entities,
      onDismiss = { adding = false },
      onPick = {
        adding = false
        onChange(CardSettings.withEntityAdded(card, it))
      },
    )
  }
}

@Composable
private fun EntityPickerDialog(
  entities: Collection<EntityState>,
  onDismiss: () -> Unit,
  onPick: (String) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Choose entity") },
    text = { EntitySearchList(entities) { onPick(it.entityId) } },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/**
 * A search field over [entities] (every word in the name or id, [entityMatches]) and the matches to
 * pick from; [header] comes before the matches, inside the same list.
 */
@Composable
internal fun EntitySearchList(
  entities: Collection<EntityState>,
  header: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
  onPick: (EntityState) -> Unit,
) {
  var query by remember { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      label = { Text("Search entities") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    LazyColumn(Modifier.heightIn(max = 320.dp)) {
      header?.invoke(this)
      items(entityMatches(entities, query), key = { it.entityId }) { state ->
        TextButton(onClick = { onPick(state) }, modifier = Modifier.fillMaxWidth()) {
          Text(
            ((state.attributes["friendly_name"] as? JsonPrimitive)
              ?.contentOrNull
              ?.let { "$it · " }
              .orEmpty()) + state.entityId,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }
}
