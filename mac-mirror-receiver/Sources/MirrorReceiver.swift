import Foundation
import Network
import AppKit
import Combine

// Wire format from the Android side, per frame:
//   [4-byte big-endian frame length][JPEG bytes]
class MirrorReceiver: ObservableObject {
    @Published var currentImage: NSImage?
    @Published var status: String = "Not started"
    @Published var androidHost: NWEndpoint.Host? // set once the phone connects; used to push files back

    private var listener: NWListener?
    private var connection: NWConnection?
    let port: UInt16 = 5050

    func start() {
        do {
            let params = NWParameters.tcp
            listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
        } catch {
            status = "Failed to start listener: \(error)"
            return
        }

        listener?.newConnectionHandler = { [weak self] newConnection in
            self?.connection = newConnection
            if case let .hostPort(host, _) = newConnection.endpoint {
                self?.androidHost = host
            }
            newConnection.start(queue: .main)
            self?.status = "Phone connected"
            self?.receiveFrameLength()
        }

        listener?.start(queue: .main)
        status = "Waiting for phone on port \(port)..."
    }

    private func receiveFrameLength() {
        guard let connection = connection else { return }

        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let error = error {
                self.status = "Connection error: \(error)"
                return
            }
            guard let data = data, data.count == 4 else {
                if isComplete { self.status = "Phone disconnected" }
                return
            }
            let length = data.bigEndianUInt32()
            self.receiveFrameBody(length: Int(length))
        }
    }

    private func receiveFrameBody(length: Int) {
        guard let connection = connection else { return }

        connection.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] data, _, _, error in
            guard let self = self else { return }
            if let data = data, let image = NSImage(data: data) {
                DispatchQueue.main.async {
                    self.currentImage = image
                }
            }
            if let error = error {
                self.status = "Connection error: \(error)"
                return
            }
            // Loop: go back to waiting for the next frame's length prefix
            self.receiveFrameLength()
        }
    }

    func stop() {
        connection?.cancel()
        listener?.cancel()
        status = "Stopped"
    }
}
