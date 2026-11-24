import unittest
import json
from pathlib import Path
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
            {"properties":{"golf":"tee","osm_type":"way","osm_id":4},"geometry":{"type":"Polygon","coordinates":[[[-97.00002,25.99998],[-96.99998,25.99998],[-96.99998,26.00002],[-97.00002,26.00002],[-97.00002,25.99998]]]}},
            {"properties":{"golf":"green","osm_type":"way","osm_id":5},"geometry":{"type":"Polygon","coordinates":[[[-96.99902,25.99998],[-96.99898,25.99998],[-96.99898,26.00002],[-96.99902,26.00002],[-96.99902,25.99998]]]}},
            {"properties":{"golf":"pin","osm_type":"node","osm_id":2},"geometry":{"type":"Point","coordinates":[-97.0,26.0]}},
        ]}
        result=audit.analyze(collection)
        hole=result["holes"][0]
        self.assertEqual(hole["line_direction"]["selected"],"forward")
        accepted=hole["endpoint_marker_candidates"]["pin"]["strong_endpoint_candidates"]
        self.assertEqual(accepted,[])

    def test_corridor_features_remain_candidates(self):
        collection={"features":[
            {"properties":{"golf":"hole","osm_id":1,"ref":"1"},"geometry":{"type":"LineString","coordinates":[[-97.0,26.0],[-96.999,26.0]]}},
            {"properties":{"golf":"bunker","osm_type":"way","osm_id":4},"geometry":{"type":"Point","coordinates":[-96.9995,26.0001]}},
        ]}
        result=audit.analyze(collection)
        self.assertEqual(result["holes"][0]["corridor_feature_candidates"][0]["assignment"],"corridor_candidate_only")

    def test_reversed_line_direction_is_inferred_from_markers(self):
        collection={"features":[
            {"properties":{"golf":"hole","osm_id":1,"ref":"1"},"geometry":{"type":"LineString","coordinates":[[-96.999,26.0],[-97.0,26.0]]}},
            {"properties":{"golf":"tee","osm_type":"way","osm_id":2},"geometry":{"type":"Polygon","coordinates":[[[-97.00002,25.99998],[-96.99998,25.99998],[-96.99998,26.00002],[-97.00002,26.00002],[-97.00002,25.99998]]]}},
            {"properties":{"golf":"green","osm_type":"way","osm_id":3},"geometry":{"type":"Polygon","coordinates":[[[-96.99902,25.99998],[-96.99898,25.99998],[-96.99898,26.00002],[-96.99902,26.00002],[-96.99902,25.99998]]]}},
        ]}
        result=audit.analyze(collection)
        hole=result["holes"][0]
        self.assertEqual(hole["line_direction"]["selected"],"reversed")
        markers=hole["endpoint_marker_candidates"]
        self.assertEqual(markers["tee"]["strong_endpoint_candidates"][0]["osm_id"],2)
        self.assertEqual(markers["green"]["strong_endpoint_candidates"][0]["osm_id"],3)

    def test_course_wide_uniqueness_keeps_marker_with_clearly_nearest_hole(self):
        results = [
            {"hole_ref": "1", "endpoint_marker_candidates": {
                "tee": {"strong_endpoint_candidates": [{"osm_type": "way", "osm_id": 10, "distance_to_start_m": 4.0}]},
                "green": {"strong_endpoint_candidates": []},
                "pin": {"strong_endpoint_candidates": []},
            }},
            {"hole_ref": "2", "endpoint_marker_candidates": {
                "tee": {"strong_endpoint_candidates": [{"osm_type": "way", "osm_id": 10, "distance_to_start_m": 18.0}]},
                "green": {"strong_endpoint_candidates": []},
                "pin": {"strong_endpoint_candidates": []},
            }},
        ]
        audit.apply_course_wide_uniqueness(results, margin_m=5.0)
        first = results[0]["endpoint_marker_candidates"]["tee"]
        second = results[1]["endpoint_marker_candidates"]["tee"]
        self.assertEqual(len(first["course_wide_unique_candidates"]), 1)
        self.assertEqual(first["course_wide_unique_candidates"][0]["osm_id"], 10)
        self.assertEqual(len(second["course_wide_unique_candidates"]), 0)
        self.assertEqual(second["closer_to_another_hole"][0]["closest_hole_ref"], "1")

    def test_course_wide_near_tie_is_left_ambiguous(self):
        results = [
            {"hole_ref": "1", "endpoint_marker_candidates": {
                "pin": {"strong_endpoint_candidates": [{"osm_type": "node", "osm_id": 20, "distance_to_end_m": 7.0}]},
                "tee": {"strong_endpoint_candidates": []},
                "green": {"strong_endpoint_candidates": []},
            }},
            {"hole_ref": "2", "endpoint_marker_candidates": {
                "pin": {"strong_endpoint_candidates": [{"osm_type": "node", "osm_id": 20, "distance_to_end_m": 10.0}]},
                "tee": {"strong_endpoint_candidates": []},
                "green": {"strong_endpoint_candidates": []},
            }},
        ]
        audit.apply_course_wide_uniqueness(results, margin_m=5.0)
        for result in results:
            pins = result["endpoint_marker_candidates"]["pin"]
            self.assertEqual(pins["course_wide_unique_candidates"], [])
            self.assertEqual(len(pins["ambiguous_shared_candidates"]), 1)
            self.assertEqual(pins["ambiguous_shared_candidates"][0]["course_wide_status"], "ambiguous_between_holes")

    def test_real_westwood_snapshot_does_not_double_claim_endpoint_markers(self):
        snapshot = Path(__file__).parent / "results" / "westwood" / "features.geojson"
        collection = json.loads(snapshot.read_text(encoding="utf-8"))
        result = audit.analyze(collection)
        self.assertEqual(result["hole_count"], 18)

        for marker_type in audit.MARKERS:
            owners = {}
            for hole in result["holes"]:
                candidates = hole["endpoint_marker_candidates"][marker_type]["course_wide_unique_candidates"]
                for candidate in candidates:
                    feature_id = (candidate["osm_type"], candidate["osm_id"])
                    owners.setdefault(feature_id, []).append(hole["hole_ref"])
            self.assertTrue(all(len(hole_refs) == 1 for hole_refs in owners.values()))

        tee_exclusions = sum(
            len(hole["endpoint_marker_candidates"]["tee"]["closer_to_another_hole"])
            for hole in result["holes"]
        )
        self.assertGreaterEqual(tee_exclusions, 4)

    def test_corridor_distance_detects_line_crossing_polygon_between_vertices(self):
        polygon = {
            "type": "Polygon",
            "coordinates": [[
                [0.9, 0.4], [1.1, 0.4], [1.1, 0.6],
                [0.9, 0.6], [0.9, 0.4]
            ]]
        }
        distance = audit.line_distance_to_geometry([(0.0, 0.5), (2.0, 0.5)], polygon, 0.5)
        self.assertAlmostEqual(distance, 0.0, places=6)

    def test_corridor_distance_detects_nearest_polygon_edge(self):
        polygon = {
            "type": "Polygon",
            "coordinates": [[
                [0.9, 0.4], [1.1, 0.4], [1.1, 0.6],
                [0.9, 0.6], [0.9, 0.4]
            ]]
        }
        distance = audit.line_distance_to_geometry([(0.0, 0.7), (2.0, 0.7)], polygon, 0.5)
        expected = 0.1 * audit.R * 3.141592653589793 / 180.0
        self.assertAlmostEqual(distance, expected, delta=0.01)


if __name__=="__main__":unittest.main()
