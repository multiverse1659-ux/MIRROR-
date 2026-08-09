import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @StateObject private var receiver = MirrorReceiver()
    @StateObject private var fileTransfer = FileTransferManager()

    var body: some View {
        VStack {
            Text(receiver.status)
                .font(.caption)
                .foregroundColor(.secondary)
                .padding(.top)

            if let image = receiver.currentImage {
                Image(nsImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(minWidth: 300, minHeight: 400)
            } else {
                Text("Waiting for phone to connect...")
                    .frame(minWidth: 300, minHeight: 400)
            }

            HStack {
                Button("Start") { receiver.start() }
                Button("Stop") { receiver.stop() }
            }
            .padding(.bottom, 4)

            Divider()

            VStack(spacing: 6) {
                Text("Drag a file here to send it to your phone")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Text(fileTransfer.status)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity, minHeight: 60)
            .background(Color.gray.opacity(0.1))
            .cornerRadius(8)
            .padding()
            .onDrop(of: [UTType.fileURL], isTargeted: nil) { providers in
                for provider in providers {
                    provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                        var droppedURL: URL?
                        if let data = item as? Data {
                            droppedURL = URL(dataRepresentation: data, relativeTo: nil)
                        } else if let url = item as? URL {
                            droppedURL = url
                        }
                        if let url = droppedURL {
                            DispatchQueue.main.async { fileTransfer.sendFile(url: url) }
                        }
                    }
                }
                return true
            }
        }
        .onAppear {
            receiver.start()
            fileTransfer.startListening()
        }
        .onChange(of: receiver.androidHost) { newHost in
            fileTransfer.androidHost = newHost
        }
    }
}
