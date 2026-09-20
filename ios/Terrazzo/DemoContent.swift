import Foundation

enum DemoContent {
  static let feed = DashboardFeed(
    version: 1,
    dashboards: [
      Dashboard(
        id: "home", title: "Home", icon: "house.fill",
        sections: [
          DashboardSection(
            id: "overview", title: "Overview",
            cards: [
              card("tile", "Kitchen light", "demo-tile", 70),
              card("entities", "Living room", "demo-entities", 176),
              card("glance", "At a glance", "demo-glance", 156),
            ]),
          DashboardSection(
            id: "outside", title: "Outside",
            cards: [
              card("weather-forecast", "Weather", "demo-weather", 280),
              card("gauge", "Repeater battery", "demo-gauge", 170),
              card("markdown", "Notes", "demo-markdown", 116),
            ]),
        ])
    ])

  static func data(for card: DashboardCard) throws -> Data {
    guard let url = Bundle.main.url(forResource: card.documentURL, withExtension: "rc") else {
      throw CocoaError(.fileNoSuchFile)
    }
    return try Data(contentsOf: url)
  }

  private static func card(
    _ type: String, _ title: String, _ resource: String, _ height: Double
  ) -> DashboardCard {
    DashboardCard(
      id: resource, type: type, title: title, documentURL: resource, height: height, entities: [],
      updateMode: .namedBindings)
  }
}
