import SwiftUI

struct DictationFormatMenu: View {
    @ObservedObject var library: LibraryStore
    private var formats: [(id: String, name: String)] {
        [("cleanup", "Nettoyage fidèle"), ("corrected", "Texte corrigé"), ("list", "Liste"), ("email", "Courriel")] + library.document.values.compactMap { key, raw in
            guard key.hasPrefix("format:"), let value = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: String], let name = value["name"] else { return nil }
            return (String(key.dropFirst(7)), name)
        }.sorted { $0.name < $1.name }
    }
    private var cloud: Bool { library.preference("formatting_engine", default: "off") == "cloud" }
    var body: some View {
        Menu {
            if !cloud { Text("Activez la correction dans les réglages pour appliquer un format.") }
            ForEach(formats, id: \.id) { format in
                Button { library.setPreference("selected_format", format.id) } label: {
                    if format.id == library.preference("selected_format", default: "cleanup") { Label(format.name, systemImage: "checkmark") }
                    else { Text(format.name) }
                }.disabled(!cloud)
            }
        } label: {
            Label(cloud ? formats.first { $0.id == library.preference("selected_format", default: "cleanup") }?.name ?? "Format" : "Texte brut", systemImage: "text.alignleft")
                .font(.system(size: 11))
        }.fixedSize().menuStyle(.borderlessButton)
    }
}
