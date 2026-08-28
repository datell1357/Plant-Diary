import Foundation

let providerAuthorityResponse = Data(
    """
    {
      "kind": "candidates",
      "candidates": [
        {
          "publicContentId": "arbitrary-provider-id",
          "koreanName": "제공자 식물",
          "commonName": "Provider common",
          "scientificName": "Provider species",
          "confidence": 0.95,
          "thumbnailUrl": "https://images.example.invalid/provider.jpg"
        }
      ]
    }
    """.utf8
)
