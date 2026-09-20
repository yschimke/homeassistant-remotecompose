import SwiftUI

struct RootView: View {
  @EnvironmentObject private var store: DashboardStore

  var body: some View {
    switch store.phase {
    case .signedOut:
      ConnectView()
    case .loading where store.feed == nil:
      ProgressView("Loading dashboards…")
    case .failed(let message) where store.feed == nil:
      ConnectView(errorMessage: message)
    default:
      DashboardShell()
    }
  }
}

private struct ConnectView: View {
  @EnvironmentObject private var store: DashboardStore
  @State private var baseURL = UserDefaults.standard.string(forKey: "serverURL")
    ?? "http://homeassistant.local:8099"
  @State private var token = CredentialStore().loadToken()
  var errorMessage: String?

  var body: some View {
    NavigationStack {
      Form {
        Section {
          VStack(alignment: .leading, spacing: 8) {
            Image(systemName: "diamond.fill")
              .font(.system(size: 44))
              .foregroundStyle(.tint)
            Text("Connect to Home Assistant").font(.largeTitle.bold())
            Text("Connect to a Terrazzo Remote Compose gateway, or explore with bundled cards.")
              .foregroundStyle(.secondary)
          }
          .listRowInsets(EdgeInsets(top: 28, leading: 0, bottom: 24, trailing: 0))
        }
        .listRowBackground(Color.clear)

        Section("Gateway") {
          TextField("Base URL", text: $baseURL)
            .textContentType(.URL)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
          SecureField("Bearer token (optional)", text: $token)
          Button("Connect") { store.connect(baseURL: baseURL, token: token) }
            .disabled(baseURL.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        if let errorMessage {
          Section { Label(errorMessage, systemImage: "exclamationmark.triangle.fill") }
            .foregroundStyle(.red)
        }
        Section {
          Button("Try demo mode (no login)") { store.startDemo() }
        } footer: {
          Text("Demo mode is offline and uses the same native Swift card player as a live dashboard.")
        }
      }
    }
  }
}

private struct DashboardShell: View {
  @EnvironmentObject private var store: DashboardStore

  var body: some View {
    NavigationStack {
      DashboardView()
        .navigationTitle(store.selectedDashboard?.title ?? "Dashboards")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
          ToolbarItem(placement: .principal) { DashboardPicker() }
          ToolbarItem(placement: .topBarTrailing) {
            Menu {
              Button("Refresh", systemImage: "arrow.clockwise") {
                Task { await store.refresh() }
              }
              Button("Settings", systemImage: "gear") { store.route = .settings }
            } label: { Image(systemName: "ellipsis.circle") }
          }
        }
        .sheet(item: $store.route) { _ in SettingsView() }
        .refreshable { await store.refresh() }
    }
  }
}

private struct DashboardPicker: View {
  @EnvironmentObject private var store: DashboardStore

  var body: some View {
    Menu {
      ForEach(store.feed?.dashboards ?? []) { dashboard in
        Button {
          store.selectedDashboardID = dashboard.id
        } label: {
          Label(
            dashboard.title,
            systemImage: dashboard.id == store.selectedDashboard?.id ? "checkmark" : "rectangle")
        }
      }
    } label: {
      HStack(spacing: 5) {
        Text(store.selectedDashboard?.title ?? "Dashboards").font(.headline)
        Image(systemName: "chevron.down").font(.caption.bold())
      }
      .foregroundStyle(.primary)
    }
  }
}

private struct DashboardView: View {
  @EnvironmentObject private var store: DashboardStore
  @State private var partialCards: Set<String> = []

  var body: some View {
    ScrollView {
      LazyVStack(spacing: 12) {
        if let message = store.streamMessage {
          Label(message, systemImage: "wifi.slash")
            .font(.caption)
            .foregroundStyle(.secondary)
            .padding(.vertical, 6)
        }
        ForEach(store.selectedDashboard?.sections ?? []) { section in
          VStack(alignment: .leading, spacing: 10) {
            if let title = section.title {
              Text(title).font(.title3.bold()).padding(.horizontal, 4)
            }
            ForEach(section.cards) { card in
              CardView(
                card: card, data: store.documents[card.id], bindings: store.bindings,
                revision: store.bindingRevision, partial: partialCards.contains(card.id),
                onAction: store.dispatch,
                onPartialChanged: { isPartial in
                  if isPartial { partialCards.insert(card.id) } else { partialCards.remove(card.id) }
                })
            }
          }
          .padding(10)
          .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
      }
      .padding(.horizontal, 12)
      .padding(.vertical, 10)
    }
    .background(Color(uiColor: .systemGroupedBackground))
    .overlay {
      if store.selectedDashboard == nil {
        ContentUnavailableView("No dashboards", systemImage: "rectangle.stack")
      }
    }
  }
}

private struct CardView: View {
  let card: DashboardCard
  let data: Data?
  let bindings: [String: BindingValue]
  let revision: Int
  let partial: Bool
  let onAction: (String) -> Void
  let onPartialChanged: (Bool) -> Void

  var body: some View {
    Group {
      if let data {
        NativeCardPlayer(
          data: data, bindings: bindings, revision: revision,
          onMetadataAction: onAction,
          onDiagnostics: { onPartialChanged($0.isPartial) })
          .accessibilityLabel(card.title ?? card.type)
      } else {
        HStack {
          ProgressView()
          Text("Loading \(card.title ?? card.type)…").foregroundStyle(.secondary)
        }
      }
    }
    .frame(maxWidth: .infinity, minHeight: card.displayHeight, maxHeight: card.displayHeight)
    .background(Color(uiColor: .secondarySystemGroupedBackground))
    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    .overlay(alignment: .topTrailing) {
      if partial {
        Image(systemName: "exclamationmark.triangle.fill")
          .font(.caption)
          .foregroundStyle(.orange)
          .padding(8)
          .accessibilityLabel("Partially supported Remote Compose document")
      }
    }
  }
}

private struct SettingsView: View {
  @EnvironmentObject private var store: DashboardStore
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    NavigationStack {
      Form {
        Section("Rendering") {
          LabeledContent("Player", value: "Native Swift / UIKit")
          LabeledContent("rc-players", value: "1.68.0")
          LabeledContent("Compatibility", value: "Compatible")
        }
        Section {
          Button("Sign out", role: .destructive) {
            dismiss()
            store.signOut()
          }
        }
      }
      .navigationTitle("Settings")
      .toolbar {
        ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
      }
    }
  }
}
