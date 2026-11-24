import SwiftUI
import VisionKit
import CoreImage.CIFilterBuiltins
import PasalaKit

struct PairView: View {
    @EnvironmentObject var engine: Engine
    @Environment(\.dismiss) private var dismiss
    @State private var tab = 0
    @State private var now = Date()
    @State private var pasted = ""
    @State private var message: String?
    @State private var working = false
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Backdrop()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack {
                        Spacer()
                        Button { dismiss() } label: { Image(systemName: "xmark").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                            .frame(width: 36, height: 36).background(Circle().fill(Theme.surface)) }
                    }
                    Text("Sumar dispositivo").font(.system(size: 30, weight: .heavy, design: .rounded))
                    Text("Uno muestra el código y el otro lo escanea. Da igual cuál: después se pueden pasar cosas para los dos lados.")
                        .font(.system(size: 15, design: .rounded)).foregroundStyle(Theme.dim)
                    Picker("", selection: $tab) {
                        Text("Mostrar mi código").tag(0)
                        Text("Escanear código").tag(1)
                    }.pickerStyle(.segmented)

                    if tab == 0 { showCode } else { scan }

                    if let message {
                        Text(message).font(.system(size: 14, weight: .semibold, design: .rounded)).foregroundStyle(Theme.orange)
                    }
                }
                .padding(20)
            }
        }
        .onReceive(timer) { now = $0 }
        .onChange(of: engine.justPaired) { _, p in if p != nil { dismiss() } }
    }

    var showCode: some View {
        VStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 30, style: .continuous).fill(Theme.brandAngular).blur(radius: 16).opacity(0.5)
                RoundedRectangle(cornerRadius: 28, style: .continuous).fill(.white)
                if let img = qr(engine.pairPayload) {
                    Image(uiImage: img).interpolation(.none).resizable().padding(22)
                }
                PasalaTile(size: 54).overlay(RoundedRectangle(cornerRadius: 12.5, style: .continuous).stroke(.white, lineWidth: 4))
            }
            .aspectRatio(1, contentMode: .fit)
            .padding(.horizontal, 16)
            .id(engine.pairCode)
            Text(engine.deviceName).font(.system(size: 20, weight: .heavy, design: .rounded))
            Text("Escanealo desde la Mac o desde otro teléfono con Pasala.").font(.system(size: 14, design: .rounded)).foregroundStyle(Theme.dim)
            let left = max(0, Int(engine.pairExpires.timeIntervalSince(now)))
            HStack {
                Text("Se renueva en \(left / 60):\(String(format: "%02d", left % 60)) · sirve una sola vez")
                    .font(.system(size: 12, design: .rounded).monospacedDigit()).foregroundStyle(Theme.dim)
                Button("Nuevo") { engine.newPairCode() }.font(.system(size: 12, weight: .bold, design: .rounded))
            }
        }
        .frame(maxWidth: .infinity)
    }

    var scan: some View {
        VStack(alignment: .leading, spacing: 14) {
            if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                QRScanner { link in pair(link) }
                    .frame(height: 340)
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Theme.brand, lineWidth: 2))
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Image(systemName: "camera.viewfinder").font(.system(size: 30)).foregroundStyle(Theme.orange)
                    Text("Usá la cámara del iPhone").font(.system(size: 18, weight: .bold, design: .rounded))
                    Text("Abrí la app Cámara, apuntá al código de la otra pantalla y tocá el aviso de Pasala.")
                        .font(.system(size: 14, design: .rounded)).foregroundStyle(Theme.dim)
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(18).card()
            }
            Text("¿Tenés el código copiado?").font(.system(size: 13, weight: .semibold, design: .rounded)).foregroundStyle(Theme.dim)
            HStack {
                TextField("pasala://…", text: $pasted).textFieldStyle(.roundedBorder).font(.system(size: 13, design: .monospaced))
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Vincular") { pair(pasted) }.disabled(pasted.isEmpty || working).buttonStyle(.borderedProminent)
            }
        }
    }

    func pair(_ link: String) {
        guard !working else { return }
        working = true; message = nil
        Task {
            message = await engine.pair(withPayload: link)
            working = false
        }
    }

    func qr(_ s: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8); f.correctionLevel = "Q"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Live camera scanner that reports the first Pasala link it sees.
struct QRScanner: UIViewControllerRepresentable {
    let onLink: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], isHighlightingEnabled: true)
        vc.delegate = context.coordinator
        try? vc.startScanning()
        return vc
    }
    func updateUIViewController(_ vc: DataScannerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onLink) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onLink: (String) -> Void
        var done = false
        init(_ f: @escaping (String) -> Void) { onLink = f }
        func dataScanner(_ s: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
            for case let .barcode(code) in items {
                if !done, let v = code.payloadStringValue, v.hasPrefix("pasala://pair") { done = true; onLink(v) }
            }
        }
    }
}
