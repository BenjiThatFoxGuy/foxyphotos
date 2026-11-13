import Foundation
import FoxyPhotosGoogleCore

/// Native file streaming only. Auth, duplicate check, upload and commit live in shared Kotlin.
final class GoogleURLSessionTransport: NSObject, GoogleHttpTransport {
    private let lock = NSLock()
    private var activeTask: URLSessionTask?

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
        let done: @Sendable (Data?, URLResponse?, Error?) -> Void = { [weak self] data, response, error in
            self?.lock.lock()
            self?.activeTask = nil
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
            task = URLSession.shared.uploadTask(with: outgoing, fromFile: URL(fileURLWithPath: path), completionHandler: done)
        } else {
            guard let encoded = request.bodyBase64, let body = Data(base64Encoded: encoded) else {
                completion.complete(response: nil, error: "Invalid request body")
                return
            }
            task = URLSession.shared.uploadTask(with: outgoing, from: body, completionHandler: done)
        }
        lock.lock()
        activeTask = task
        lock.unlock()
        task.resume()
    }

    func cancelActive() {
        lock.lock()
        let task = activeTask
        activeTask = nil
        lock.unlock()
        task?.cancel()
    }
}
