import SwiftUI
import PasalaKit

struct SettingsView: View {
    @EnvironmentObject var engine: Engine
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var savePhotos = PhotoSaver.enabled

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Nombre", text: $name).onSubmit { engine.setDeviceName(name) }
                } header: { Text("Este teléfono") } footer: { Text("Así te ven tus otros dispositivos.") }

                Section {
                    Toggle("Pedir confirmación antes de recibir", isOn: Binding(get: { engine.askBeforeReceiving }, set: { engine.setAsk($0) }))
                    Toggle("Guardar fotos y videos en Fotos", isOn: $savePhotos)
                        .onChange(of: savePhotos) { _, v in PhotoSaver.enabled = v }
                } header: { Text("Al recibir") } footer: {
                    Text("Todo lo que llega queda en Archivos › En mi iPhone › Pasala. El iPhone recibe mientras Pasala está abierta.")
                }

                Section("Detalles") {
                    LabeledContent("Direcciones", value: engine.addrs.joined(separator: ", "))
                    LabeledContent("Puerto", value: String(engine.port))
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.ink)
            .navigationTitle("Ajustes")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Listo") { engine.setDeviceName(name); dismiss() } } }
        }
        .onAppear { name = engine.deviceName }
    }
}
