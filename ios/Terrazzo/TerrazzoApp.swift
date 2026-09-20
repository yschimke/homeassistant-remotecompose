import SwiftUI

@main
struct TerrazzoApp: App {
  @StateObject private var store = DashboardStore()

  var body: some Scene {
    WindowGroup {
      RootView()
        .environmentObject(store)
        .tint(Color(red: 0.02, green: 0.52, blue: 0.78))
    }
  }
}
