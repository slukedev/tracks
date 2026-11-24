import SwiftUI

/// The software Tracks is built with, and the licenses it's used under: Tracks' own, then
/// the engine's. On iPhone a page under About; on the Mac a sheet from the General pane.
struct AcknowledgementsPage: View {
    var body: some View {
        Form {
            Section {
                SettingsLinkRow(
                    title: "GNU General Public License v3.0",
                    systemImage: "chevron.left.forward.slash.chevron.right",
                    tint: .gray,
                    destination: URL(string: "https://www.gnu.org/licenses/gpl-3.0.html")!
                )
            } header: {
                Text("Tracks")
            }
            Section {
                ForEach(Acknowledgement.engine) { item in
                    AcknowledgementRow(item: item)
                }
            } header: {
                Text("Engine")
            }
        }
        #if os(iOS)
        .navigationTitle("Acknowledgements")
        .navigationBarTitleDisplayMode(.inline)
        #else
        .formStyle(.grouped)
        #endif
    }
}

/// One library: its name and license, opening to the license's text where Tracks carries it.
private struct AcknowledgementRow: View {
    let item: Acknowledgement

    var body: some View {
        if let text = item.text {
            DisclosureGroup {
                Text(verbatim: text)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            } label: {
                label
            }
        } else {
            label
        }
    }

    private var label: some View {
        LabeledContent {
            Text(verbatim: item.license)
        } label: {
            Text(verbatim: item.name)
        }
    }
}

private struct Acknowledgement: Identifiable {
    let name: String
    let license: String
    var text: String?

    var id: String { name }

    static var engine: [Acknowledgement] {
        var all = [
            Acknowledgement(name: "UniFFI", license: "MPL 2.0"),
            Acknowledgement(name: "SQLite", license: "Public Domain"),
            Acknowledgement(name: "rusqlite, serde, jiff and others", license: "MIT, Apache 2.0"),
        ]
        #if SPOTIFY
        all += [
            Acknowledgement(name: "librespot", license: "MIT", text: librespot),
            Acknowledgement(name: "psst", license: "MIT", text: psst),
            Acknowledgement(name: "Symphonia", license: "MPL 2.0"),
            Acknowledgement(name: "webpki-roots", license: "CDLA Permissive 2.0"),
            Acknowledgement(name: "Tokio, rustls and others", license: "MIT, Apache 2.0"),
        ]
        #endif
        return all
    }

    #if SPOTIFY
    private static let mitTerms = """
    Permission is hereby granted, free of charge, to any person obtaining a copy of this \
    software and associated documentation files (the "Software"), to deal in the Software \
    without restriction, including without limitation the rights to use, copy, modify, merge, \
    publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons \
    to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or \
    substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, \
    INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR \
    PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE \
    FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR \
    OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER \
    DEALINGS IN THE SOFTWARE.
    """

    private static let librespot = """
    librespot (https://github.com/librespot-org/librespot)

    The MIT License (MIT)

    Copyright (c) 2015 Paul Lietar

    """ + mitTerms

    private static let psst = """
    psst (https://github.com/jpochyla/psst)

    MIT License

    Copyright (c) 2020 Jan Pochyla

    """ + mitTerms
    #endif
}

/// The About section's way in: a pushed page on iPhone, a sheet in the Mac's pane.
struct AcknowledgementsRow: View {
    #if os(macOS)
    @State private var isShowing = false
    #endif

    var body: some View {
        #if os(iOS)
        NavigationLink {
            AcknowledgementsPage()
        } label: {
            Label {
                Text("Acknowledgements")
            } icon: {
                SettingsIconTile(systemImage: "doc.text", color: .gray)
            }
        }
        #else
        LabeledContent("Acknowledgements") {
            Button("Show…") { isShowing = true }
        }
        .sheet(isPresented: $isShowing) {
            NavigationStack {
                AcknowledgementsPage()
                    .navigationTitle("Acknowledgements")
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { isShowing = false }
                        }
                    }
            }
            .frame(width: 480, height: 520)
        }
        #endif
    }
}
