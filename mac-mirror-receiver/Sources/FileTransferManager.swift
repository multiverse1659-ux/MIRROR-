import Foundation
import Network

// Wire format (same in both directions), matching the Android side:
//   [4-byte big-endian filename length][filename UTF-8 bytes]
//   [8-byte big-endian file length][file bytes]
class FileTransferManager: ObservableObject {
    @Published var status: String = "No files yet"
    var androidHost: NWEndpoint.Host?

    private var listener: NWListener?
    let receivePort: UInt16 = 5052 // phone connects here to push a file to this Mac
    let sendPort: UInt16 = 5051    // this Mac connects here to push a file to the phone

    func startListening() {
        do {
            listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: receivePort)!)
        } catch {
            status = "Failed to start file listener: \(error)"
            return
        }
        listener?.newConnectionHandler = { [weak self] connection in
            connection.start(queue: .main)
            self?.receiveFile(on: connection)
        }
        listener?.start(queue: .main)
    }

    // MARK: - Receiving (phone -> Mac)

    private func receiveFile(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, _, error in
            guard let self = self, let data = data, data.count == 4, error == nil else { return }
            let nameLength = Int(data.bigEndianUInt32())

            connection.receive(minimumIncompleteLength: nameLength, maximumLength: nameLength) { nameData, _, _, _ in
                guard let nameData = nameData, let fileName = String(data: nameData, encoding: .utf8) else { return }

                connection.receive(minimumIncompleteLength: 8, maximumLength: 8) { lenData, _, _, _ in
                    guard let lenData = lenData, lenData.count == 8 else { return }
                    let fileLength = Int(lenData.bigEndianUInt64())
                    self.receiveFileBody(connection: connection, fileName: fileName, remaining: fileLength, accumulated: Data())
                }
            }
        }
    }

    private func receiveFileBody(connection: NWConnection, fileName: String, remaining: Int, accumulated: Data) {
        if remaining <= 0 {
            saveFile(named: fileName, data: accumulated)
            connection.cancel()
            return
        }
        let chunkSize = min(remaining, 65536)
        connection.receive(minimumIncompleteLength: 1, maximumLength: chunkSize) { [weak self] data, _, _, _ in
            guard let self = self, let data = data else { return }
            var newAccumulated = accumulated
            newAccumulated.append(data)
            self.receiveFileBody(connection: connection, fileName: fileName, remaining: remaining - data.count, accumulated: newAccumulated)
        }
    }

    private func saveFile(named fileName: String, data: Data) {
        guard let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first else { return }
        let destination = downloads.appendingPathComponent(fileName)
        do {
            try data.write(to: destination)
            DispatchQueue.main.async { self.status = "Received \(fileName) → ~/Downloads" }
        } catch {
            DispatchQueue.main.async { self.status = "Failed to save \(fileName): \(error)" }
        }
    }

    // MARK: - Sending (Mac -> phone)

    func sendFile(url: URL) {
        guard let host = androidHost else {
            status = "Phone not connected yet — start screen mirroring first"
            return
        }
        guard let data = try? Data(contentsOf: url) else {
            status = "Couldn't read \(url.lastPathComponent)"
            return
        }
        let fileName = url.lastPathComponent
        let connection = NWConnection(host: host, port: NWEndpoint.Port(rawValue: sendPort)!, using: .tcp)
        connection.start(queue: .main)

        var payload = Data()
        let nameBytes = Array(fileName.utf8)
        var nameLength = UInt32(nameBytes.count).bigEndian
        payload.append(Data(bytes: &nameLength, count: 4))
        payload.append(contentsOf: nameBytes)
        var fileLength = UInt64(data.count).bigEndian
        payload.append(Data(bytes: &fileLength, count: 8))
        payload.append(data)

        connection.send(content: payload, completion: .contentProcessed { [weak self] error in
            DispatchQueue.main.async {
                self?.status = error == nil ? "Sent \(fileName) to phone" : "Send failed: \(error!)"
                connection.cancel()
            }
        })
    }
}
