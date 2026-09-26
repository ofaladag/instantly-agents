// Independent fixture generator: xcrun swift ios-envelope-vector.swift > ios-envelope-vector.json
import Foundation
import CryptoKit
func b64(_ data: Data) -> String { data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
func bytes(_ value: String) -> Data { var uuid = UUID(uuidString: value)!.uuid; return withUnsafeBytes(of: &uuid) { Data($0) } }
let alice = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((0..<32).map(UInt8.init)))
let bob = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((32..<64).map(UInt8.init)))
let conversation = "00112233-4455-6677-8899-aabbccddeeff"
let sender = "11111111-2222-3333-4444-555555555555"
let recipient = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
let aad = Data([1]) + bytes(conversation) + bytes(sender) + bytes(recipient)
let shared = try alice.sharedSecretFromKeyAgreement(with: bob.publicKey)
let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data("instantly-chat-msg-v1".utf8), sharedInfo: aad, outputByteCount: 32)
let plaintext = "{\"t\":\"text\",\"body\":\"Merhaba 👋 Ça va?\",\"sentAt\":\"2026-09-26T12:00:00Z\"}"
let sealed = try ChaChaPoly.seal(Data(plaintext.utf8), using: key, nonce: ChaChaPoly.Nonce(data: Data((0..<12).map(UInt8.init))), authenticating: aad)
let fixture = ["conversation": conversation, "sender": sender, "recipient": recipient, "senderPublic": b64(alice.publicKey.rawRepresentation), "recipientPrivate": b64(bob.rawRepresentation), "envelope": b64(Data([1]) + sealed.combined), "plaintext": plaintext]
let data = try JSONSerialization.data(withJSONObject: fixture, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
print(String(data: data, encoding: .utf8)!)
