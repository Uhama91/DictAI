import SwiftUI

struct AppUpdateBanner: View {
    @ObservedObject var updates: AppUpdateChecker
    var body: some View {
        if let offer = updates.offer {
            HStack(spacing: 20) {
                Image(systemName: "arrow.down.circle").font(.title2).foregroundStyle(Theme.accent)
                Text("DictAI \(offer.version) est disponible").font(.system(size: 14, weight: .semibold))
                Spacer()
                Link("Télécharger la mise à jour ↗", destination: offer.downloadURL)
                    .font(.system(size: 13, weight: .medium))
            }
            .padding(.horizontal, 24).padding(.vertical, 16)
            .foregroundStyle(Theme.cream).background(Theme.surface)
            .overlay(alignment: .bottom) { Rectangle().fill(Theme.accent.opacity(0.4)).frame(height: 1) }
        }
    }
}

struct AppUpdateSettings: View {
    @ObservedObject var updates: AppUpdateChecker
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(updates.statusText).font(.system(size: 13)).foregroundStyle(Theme.muted)
            HStack(spacing: 16) {
                Button("Vérifier les mises à jour") { Task { await updates.check(force: true) } }
                    .disabled(updates.status == .checking)
                if let offer = updates.offer { Link("Télécharger DictAI \(offer.version) ↗", destination: offer.downloadURL) }
            }
            Text("Le téléchargement s’ouvre dans votre navigateur. Lancez ensuite l’installateur pour mettre à jour DictAI.")
                .font(.caption).foregroundStyle(Theme.muted)
        }
    }
}

struct AppUpdateMenu: View {
    @ObservedObject var updates: AppUpdateChecker
    var body: some View {
        if let offer = updates.offer { Link("Télécharger DictAI \(offer.version)…", destination: offer.downloadURL) }
        Button("Vérifier les mises à jour…") { Task { await updates.check(force: true) } }
            .disabled(updates.status == .checking)
        if updates.status != .idle { Text(updates.statusText).font(.caption) }
    }
}
