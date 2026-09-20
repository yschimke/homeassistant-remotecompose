import Foundation

enum NativeTextDirection {
  case leftToRight
  case rightToLeft
}

enum NativeResolvedTextAlignment: Equatable {
  case left
  case right
  case center
  case justified
}

enum NativeResolvedTextOverflow: Equatable {
  case clip
  case visible
  case tail
  case head
  case middle
}

enum NativeResolvedLineBreak: Equatable {
  case clip
  case wordWrap
  case tail
  case head
  case middle
}

enum NativeTextPolicy {
  static func alignment(
    value: Int, justified: Bool, direction: NativeTextDirection
  ) -> NativeResolvedTextAlignment {
    if justified { return .justified }
    switch value {
    case 1: return .left
    case 2: return .right
    case 3: return .center
    case 6: return direction == .rightToLeft ? .left : .right
    default: return direction == .rightToLeft ? .right : .left
    }
  }

  static func overflow(_ value: Int) -> NativeResolvedTextOverflow {
    switch value {
    case 2: return .visible
    case 3: return .tail
    case 4: return .head
    case 5: return .middle
    default: return .clip
    }
  }

  static func numberOfLines(overflow: Int, maximum: Int) -> Int {
    maximum > 1 && (overflow == 1 || overflow == 2) ? 0 : max(maximum, 1)
  }

  static func lineBreak(overflow: Int) -> NativeResolvedLineBreak {
    switch self.overflow(overflow) {
    case .clip: return .clip
    case .visible: return .wordWrap
    case .tail: return .tail
    case .head: return .head
    case .middle: return .middle
    }
  }
}
