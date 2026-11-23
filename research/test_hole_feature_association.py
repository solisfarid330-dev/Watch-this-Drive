import unittest
import hole_feature_association as audit

class EndpointAssociationTests(unittest.TestCase):
    def test_south_padre_like_line_direction_finds_tee_and_green(self):
        collection={"features":[
            {"properties":{"golf":"hole","osm_id":1,"ref":"1"},"geometry":{"type":"LineString","coordinates":[[-97.0,26.0],[-96.999,26.0]]}},
            {"properties":{"golf":"tee","osm_type":"way","osm_id":2},"geometry":{"type":"Polygon","coordinates":[[[-97.00002,25.99998],[-96.99998,25.99998],[-96.99998,26.00002],[-97.00002,26.00002],[-97.00002,25.99998]]]}},
            {"properties":{"golf":"green","osm_type":"way","osm_id":3},"geometry":{"type":"Polygon","coordinates":[[[-96.99902,25.99998],[-96.99898,25.99998],[-96.99898,26.00002],[-96.99902,26.00002],[-96.99902,25.99998]]]}},
        ]}
        result=audit.analyze(collection)
        markers=result["holes"][0]["endpoint_marker_candidates"]
        self.assertEqual(markers["tee"]["strong_endpoint_candidates"][0]["osm_id"],2)
        self.assertEqual(markers["green"]["strong_endpoint_candidates"][0]["osm_id"],3)

    def test_pin_near_start_is_not_assigned_as_green_end(self):
        collection={"features":[
            {"properties":{"golf":"hole","osm_id":1,"ref":"1"},"geometry":{"type":"LineString","coordinates":[[-97.0,26.0],[-96.999,26.0]]}},
            {"properties":{"golf":"pin","osm_type":"node","osm_id":2},"geometry":{"type":"Point","coordinates":[-97.0,26.0]}},
            {"properties":{"golf":"pin","osm_type":"node","osm_id":3},"geometry":{"type":"Point","coordinates":[-96.999,26.0]}},
        ]}
        result=audit.analyze(collection)
        accepted=result["holes"][0]["endpoint_marker_candidates"]["pin"]["strong_endpoint_candidates"]
        self.assertEqual([x["osm_id"] for x in accepted],[3])

    def test_corridor_features_remain_candidates(self):
        collection={"features":[
            {"properties":{"golf":"hole","osm_id":1,"ref":"1"},"geometry":{"type":"LineString","coordinates":[[-97.0,26.0],[-96.999,26.0]]}},
            {"properties":{"golf":"bunker","osm_type":"way","osm_id":4},"geometry":{"type":"Point","coordinates":[-96.9995,26.0001]}},
        ]}
        result=audit.analyze(collection)
        self.assertEqual(result["holes"][0]["corridor_feature_candidates"][0]["assignment"],"corridor_candidate_only")

if __name__=="__main__":unittest.main()
