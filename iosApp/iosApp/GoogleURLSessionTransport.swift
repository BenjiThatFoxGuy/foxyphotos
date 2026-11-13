import Foundation
import FoxyPhotosGoogleCore

/// Native file streaming only. Auth, duplicate check, upload and commit live in shared Kotlin.
final class GoogleURLSessionTransport: NSObject, GoogleHttpTransport, URLSessionTaskDelegate {
    private let lock = NSLock()
    private lazy var session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
    private var activeTasks: [UUID: URLSessionTask] = [:]

    // Never forward a bearer or auth-form request to a redirect target.
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    func send(request: GoogleHttpRequest, completion: GoogleHttpCompletion) {
        guard let url = URL(string: request.url),
              url.scheme == "https",
              ["android.googleapis.com", "photos.googleapis.com", "photosdata-pa.googleapis.com"].contains(url.host) else {
            completion.complete(response: nil, error: "Invalid provider URL")
            return
        }
        var outgoing = URLRequest(url: url)
        outgoing.httpMethod = request.method
        for (name, value) in request.headers { outgoing.setValue(value, forHTTPHeaderField: name) }
        let token = UUID()
        let done: @Sendable (Data?, URLResponse?, Error?) -> Void = { [weak self] data, response, error in
            self?.lock.lock()
            self?.activeTasks.removeValue(forKey: token)
            self?.lock.unlock()
            guard error == nil, let http = response as? HTTPURLResponse, let data,
                  data.count <= 8 * 1024 * 1024 else {
                completion.complete(response: nil, error: "Provider transport failed")
                return
            }
            var headers: [String: String] = [:]
            for (key, value) in http.allHeaderFields {
                if let key = key as? String, let value = value as? String { headers[key] = value }
            }
            completion.complete(response: GoogleHttpResponse(
                status: Int32(http.statusCode), headers: headers, bodyBase64: data.base64EncodedString()
            ), error: nil)
        }
        let task: URLSessionTask
        if let path = request.originalFilePath {
            task = session.uploadTask(with: outgoing, fromFile: URL(fileURLWithPath: path), completionHandler: done)
        } else {
            guard let encoded = request.bodyBase64, let body = Data(base64Encoded: encoded) else {
                completion.complete(response: nil, error: "Invalid request body")
                return
            }
            task = session.uploadTask(with: outgoing, from: body, completionHandler: done)
        }
        lock.lock()
        activeTasks[token] = task
        lock.unlock()
        task.resume()
    }

    func cancelActive() {
        lock.lock()
        let tasks = Array(activeTasks.values)
        activeTasks.removeAll()
        lock.unlock()
        tasks.forEach { $0.cancel() }
    }
}
