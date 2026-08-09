import Foundation

// Data.withUnsafeBytes { $0.load(as: UInt32.self) } is unsafe here: Data
// buffers coming off a network connection aren't guaranteed to be aligned
// to the loaded type's requirement, and `load(as:)` on misaligned memory
// can crash. Decoding byte-by-byte sidesteps alignment entirely.
extension Data {
    func bigEndianUInt32() -> UInt32 {
        precondition(count >= 4)
        return withUnsafeBytes { raw -> UInt32 in
            var value: UInt32 = 0
            for i in 0..<4 {
                value = (value << 8) | UInt32(raw[i])
            }
            return value
        }
    }

    func bigEndianUInt64() -> UInt64 {
        precondition(count >= 8)
        return withUnsafeBytes { raw -> UInt64 in
            var value: UInt64 = 0
            for i in 0..<8 {
                value = (value << 8) | UInt64(raw[i])
            }
            return value
        }
    }
}
