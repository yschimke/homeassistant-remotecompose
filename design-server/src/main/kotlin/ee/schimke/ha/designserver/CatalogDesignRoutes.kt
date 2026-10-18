package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignKey
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class CatalogPublishRequest(
  val id: String,
  val expectedRevision: Long,
  val contractDesign: String,
  val key: DesignKey,
  val source: JsonObject,
  val validateOnly: Boolean = false,
)

/** Admin rights are an explicit allowlist, independent of the public editor's write permission. */
fun Route.catalogDesignRoutes(
  holder: LibraryHolder,
  store: CatalogDesignStore,
  builder: UiBuilderIntegration?,
  github: GithubAuth?,
  admins: Set<String>,
) {
  suspend fun ApplicationCall.admin(): String? {
    response.header(HttpHeaders.CacheControl, "no-store")
    val actor =
      github?.login(this)?.let { "github:$it" }
        ?: builder?.client?.catalogActor(request.header(HttpHeaders.Cookie))
    if (actor == null || actor !in admins) {
      respondText("Catalog administrator sign-in required", status = HttpStatusCode.Forbidden)
      return null
    }
    return actor
  }

  get("/api/designs/catalog/publications") {
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.allowAnyOrigin()
    call.respondText(
      Json.encodeToString(CatalogDesignIndex.serializer(), store.snapshot()),
      ContentType.Application.Json,
    )
  }

  get("/api/designs/catalog/source") {
    call.admin() ?: return@get
    val client =
      builder?.client
        ?: return@get call.respondText(
          "Builder unavailable",
          status = HttpStatusCode.ServiceUnavailable,
        )
    val document =
      try {
        client.source(
          call.request.queryParameters["id"].orEmpty(),
          call.request.queryParameters["revision"]?.toLongOrNull(),
          call.request.header(HttpHeaders.Cookie),
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@get call.respondText("Cannot read that source", status = HttpStatusCode.BadGateway)
      }
    call.respondText(document.toString(), ContentType.Application.Json)
  }

  post("/api/designs/catalog/edit") {
    val actor = call.admin() ?: return@post
    if (call.request.contentType().withoutParameters() != ContentType.Application.Json)
      return@post call.respondText("JSON required", status = HttpStatusCode.UnsupportedMediaType)
    val integration =
      builder
        ?: return@post call.respondText(
          "Builder unavailable",
          status = HttpStatusCode.ServiceUnavailable,
        )
    try {
      val text = call.receiveText()
      require(text.length <= 1_000_000)
      val source = Json.parseToJsonElement(text).jsonObject
      val draft =
        JsonObject(
          source +
            mapOf(
              "id" to JsonPrimitive("catalog-${UiBuilderConfig.randomToken()}"),
              "revision" to JsonPrimitive(0),
            ) - "template"
        )
      val id = integration.client.create(draft)
      integration.client.share(id, actor, editor = true)
      call.respondText(
        buildJsonObject {
          put("id", id)
          put("url", integration.config.designUrl(id))
        }
          .toString(),
        ContentType.Application.Json,
        HttpStatusCode.Created,
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      call.respondText(
        "Could not open source; existing designs unchanged",
        status = HttpStatusCode.UnprocessableEntity,
      )
    }
  }

  // JSON content type is mandatory. Cross-origin simple forms cannot publish using a session
  // cookie.
  post("/api/designs/catalog/publish") {
    val actor = call.admin() ?: return@post
    if (call.request.contentType().withoutParameters() != ContentType.Application.Json)
      return@post call.respondText("JSON required", status = HttpStatusCode.UnsupportedMediaType)
    val request =
      try {
        val text = call.receiveText()
        require(text.length <= 1_000_000)
        Json.decodeFromString(CatalogPublishRequest.serializer(), text)
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText(
          "Invalid publication request",
          status = HttpStatusCode.BadRequest,
        )
      }
    val client =
      builder?.client
        ?: return@post call.respondText(
          "Builder unavailable",
          status = HttpStatusCode.ServiceUnavailable,
        )
    val contractLibrary = holder.baseline()
    val base =
      contractLibrary.design(request.contractDesign)
        ?: return@post call.respondText(
          "Unknown binding contract",
          status = HttpStatusCode.UnprocessableEntity,
        )
    // A new style/variant may target an existing supported shape. New capabilities need an app
    // release.
    if (
      request.key.copy(theme = base.key.theme, variant = base.key.variant) != base.key ||
        request.expectedRevision < 0
    )
      return@post call.respondText(
        "Unsupported card contract",
        status = HttpStatusCode.UnprocessableEntity,
      )
    if (
      !Regex("[a-z0-9][a-z0-9-]{0,79}").matches(request.id) ||
        !Regex("[a-z0-9][a-z0-9-]{0,39}").matches(request.key.variant) ||
        !Regex("(?:style|baked):[A-Za-z0-9][A-Za-z0-9-]{0,79}:(?:light|dark)")
          .matches(request.key.theme)
    )
      return@post call.respondText(
        "Invalid publication identity or theme",
        status = HttpStatusCode.BadRequest,
      )
    catalogSourceProblem(request.source)?.let {
      return@post call.respondText(it, status = HttpStatusCode.UnprocessableEntity)
    }
    val compilerVersion =
      try {
        client.compilerVersion()
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText(
          "Released compiler identity unavailable",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
    val bytes =
      try {
        client.compileSource(request.source)
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText(
          "Pinned catalog compilation failed; publication unchanged",
          status = HttpStatusCode.UnprocessableEntity,
        )
      }
    val baseBytes = requireNotNull(contractLibrary.blob(base.entry.sha256))
    catalogTemplateProblem(bytes, baseBytes, request.key)?.let {
      return@post call.respondText(it, status = HttpStatusCode.UnprocessableEntity)
    }
    val reproduced =
      try {
        client.compileSource(request.source)
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return@post call.respondText(
          "Reproduction failed; publication unchanged",
          status = HttpStatusCode.UnprocessableEntity,
        )
      }
    if (!bytes.contentEquals(reproduced) || client.compilerVersion() != compilerVersion)
      return@post call.respondText(
        "Compiler output is not reproducible",
        status = HttpStatusCode.UnprocessableEntity,
      )
    if (request.validateOnly)
      return@post call.respondText(
        buildJsonObject {
          put("compilerVersion", compilerVersion)
          put("rc", java.util.Base64.getEncoder().encodeToString(bytes))
        }
          .toString(),
        ContentType.Application.Json,
      )
    val publication =
      try {
        store.publish(
          request.id,
          request.expectedRevision,
          request.source,
          request.key,
          bytes,
          base.entry.slots,
          base.catalog?.example,
          actor,
          request.contractDesign,
          compilerVersion = compilerVersion,
        )
      } catch (e: IllegalStateException) {
        return@post call.respondText(
          "Publication changed or key already owned; reload before publishing",
          status = HttpStatusCode.Conflict,
        )
      } catch (e: IllegalArgumentException) {
        return@post call.respondText("Invalid design id", status = HttpStatusCode.BadRequest)
      }
    call.respondText(
      Json.encodeToString(CatalogDesignRevision.serializer(), publication),
      ContentType.Application.Json,
      HttpStatusCode.Created,
    )
  }

  get("/studio/catalog") {
    call.admin() ?: return@get
    call.respondText(catalogAdminPage, ContentType.Text.Html)
  }
}

private val catalogAdminPage =
  """
    <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>Catalog publications</title><style>body{font:16px system-ui;max-width:960px;margin:auto;padding:24px}label{display:block;margin:12px 0}input,textarea,button{font:inherit}textarea{width:100%;box-sizing:border-box}pre{white-space:pre-wrap}button{margin:8px}</style>
    <h1>Catalog publications</h1><p>Edit a source in <a href="/ui-builder/ha-cards/">UI Builder</a>, then load its committed revision or import a .uid file. Publishing validates and compiles that exact source. Previous publications remain recoverable below.</p>
    <label>Builder design ID <input id="builderId"></label><button id="load">Load committed source</button>
    <label>Import .uid <input id="file" type="file" accept=".uid,application/json"></label>
    <label>Publication ID <input id="id" pattern="[a-z0-9][a-z0-9-]{0,79}"></label>
    <label>Expected publication revision (0 for new) <input id="revision" type="number" value="0" min="0"></label>
    <label>Existing card contract ID <input id="contract"></label>
    <label>Runtime key (JSON; preserve contract fields, choose theme/variant) <textarea id="key" rows="4"></textarea></label>
    <label>Authoritative source (.uid) <textarea id="source" rows="20"></textarea></label>
    <button id="edit">Open source as a new editor draft</button><button id="validate">Validate and preview</button><button id="publish">Validate, generate .rc and publish</button><p role="status" id="status"></p>
    <h2>Generated .rc preview</h2><label>Width <select id="width"><option>160</option><option selected>360</option><option>800</option></select> dp</label><canvas id="preview" style="max-width:100%;border:1px solid #aaa"></canvas><label>Test live values (JSON by binding name)<textarea id="bindings" rows="4">{}</textarea></label><button id="push">Push values without regenerating</button><p id="actions" role="status"></p>
  <h2>Revision history</h2><p>Load a retained source to recover it, then publish against the current revision. Recovery creates a new revision.</p><div id="history"></div>
    <script src="/embed/v1/rc-player.js"></script><script type="module">
    import { patchStringOverrides } from "/embed/v1/ha-card.js";
    const el=id=>document.getElementById(id);
    async function action(fn){try{await fn()}catch(e){el('status').textContent=e.message}}
    async function read(response){if(!response.ok)throw Error(await response.text());return response.json()}
    el('file').onchange=()=>action(async()=>{el('source').value=await el('file').files[0].text()});
    el('load').onclick=()=>action(async()=>{const doc=await read(await fetch('/api/designs/catalog/source?id='+encodeURIComponent(el('builderId').value)));el('source').value=JSON.stringify(doc,null,2)});
    el('edit').onclick=()=>action(async()=>{const draft=await read(await fetch('/api/designs/catalog/edit',{method:'POST',headers:{'Content-Type':'application/json'},body:el('source').value}));el('builderId').value=draft.id;window.open(draft.url,'_blank','noopener')});
    async function submit(validateOnly){el('status').textContent='Validating and compiling…';const p=await read(await fetch('/api/designs/catalog/publish',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:el('id').value,expectedRevision:Number(el('revision').value),contractDesign:el('contract').value,key:JSON.parse(el('key').value),source:JSON.parse(el('source').value),validateOnly})}));if(validateOnly){await preview(Uint8Array.from(atob(p.rc),c=>c.charCodeAt(0)).buffer);el('status').textContent='Validated with compiler '+p.compilerVersion}else{el('revision').value=p.revision;el('status').textContent='Published revision '+p.revision;await preview(await (await fetch('/designs/v1/blobs/'+p.entry.sha256+'.rc')).arrayBuffer());await history()}}
  el('publish').onclick=()=>action(()=>submit(false));el('validate').onclick=()=>action(()=>submit(true));
    let player;
  async function preview(bytes){player?.destroy();const canvas=el('preview');canvas.width=Number(el('width').value)*2;canvas.height=Math.min(600,JSON.parse(el('source').value).environment?.heightDp||240)*2;canvas.style.width=el('width').value+'px';player=new RC.RcdPlayer(canvas);player.setDensity(2);const doc=await new Promise((resolve,reject)=>{player.setOnLoad(resolve);player.loadFromArrayBuffer(bytes).catch(reject)});patchStringOverrides(player.getRemoteContext());doc.addActionCallback({onAction:(name,value)=>{el('actions').textContent='Preview action: '+name+' '+JSON.stringify(value)}});player.repaint()}
  el('width').onchange=()=>{if(player){const width=Number(el('width').value)*2;el('preview').style.width=el('width').value+'px';player.resize(width,el('preview').height);player.repaint()}};
  el('push').onclick=()=>action(async()=>{if(!player)throw Error('Validate a source first');const ctx=player.getRemoteContext();for(const [name,value]of Object.entries(JSON.parse(el('bindings').value))){const key=name.startsWith('USER:')?name:'USER:'+name;const variable=ctx.namedVariables.get(key);if(!variable)throw Error('Unknown binding: '+name);if(variable.type===0)ctx.setNamedStringOverride(key,String(value));else if(variable.type===1)ctx.setNamedFloatOverride(key,Number(value));else if(variable.type===4)ctx.setNamedIntegerOverride(key,Number(value));else throw Error('Unsupported test binding type')}player.getDocument()?.invalidateMeasure?.();player.repaint()});
  async function history(){const data=await read(await fetch('/api/designs/catalog/publications'));el('history').replaceChildren();for(const p of [...data.revisions].reverse()){const row=document.createElement('p'),button=document.createElement('button'),link=document.createElement('a');button.textContent=p.id+' · revision '+p.revision;button.onclick=()=>{el('id').value=p.id;el('revision').value=Math.max(...data.revisions.filter(r=>r.id===p.id).map(r=>r.revision));el('contract').value=p.contractDesign;el('key').value=JSON.stringify(p.entry.key,null,2);el('source').value=JSON.stringify(p.source,null,2)};link.textContent='Open design';link.href='/designs/'+encodeURIComponent(p.id);row.append(button,link);el('history').append(row)}}
    action(history);
    </script></html>
  """
    .trimIndent()
