import unittest

from shapely.geometry import Point, box
from shapely.ops import unary_union

from scripts.course_intelligence.reconstruction import propose_hole_routes_from_features


def candidate(identifier, kind, geometry):
    return {
        "type": "Feature",
        "id": identifier,
        "geometry": None,
        "properties": {"feature_class": kind},
        "_shape": geometry,
    }


class ImageryRouteHypothesisTests(unittest.TestCase):
    def test_proposes_routes_without_osm_hole_centerlines(self):
        features = [
            candidate("tee-1", "tee", Point(-97.0, 32.0)),
            candidate("green-1", "green", Point(-96.999, 32.0)),
            candidate("fairway-1", "fairway", box(-97.0002, 31.9999, -96.9988, 32.0001)),
        ]
        routes, summary = propose_hole_routes_from_features(features)
        self.assertEqual(summary["route_hypotheses"], 1)
        self.assertEqual(routes[0]["properties"]["tee_candidate_id"], "tee-1")
        self.assertEqual(routes[0]["properties"]["green_candidate_id"], "green-1")
        self.assertEqual(
            routes[0]["properties"]["validation_status"],
            "requires_visual_review",
        )
        self.assertEqual(routes[0]["properties"]["association_status"], "unambiguous_candidate")

    def test_preserves_ambiguous_alternative_routes(self):
        features = [
            candidate("tee-1", "tee", Point(-97.0, 32.0)),
            candidate("green-a", "green", Point(-96.9995, 32.0)),
            candidate("green-b", "green", Point(-96.9996, 32.0)),
        ]
        routes, summary = propose_hole_routes_from_features(
            features, ambiguity_score_margin=0.12
        )
        self.assertEqual(summary["route_hypotheses"], 2)
        self.assertTrue(all(
            route["properties"]["association_status"] == "ambiguous"
            for route in routes
        ))
        self.assertTrue(all(
            route["properties"]["alternative_route_hypothesis_ids"]
            for route in routes
        ))

    def test_fairway_shape_produces_bent_route_hypothesis(self):
        fairway = unary_union([
            box(-97.00008, 31.99995, -96.99992, 32.00105),
            box(-97.00002, 32.00092, -96.99885, 32.00108),
        ])
        features = [
            candidate("tee-bend", "tee", Point(-97.0, 32.0)),
            candidate("green-bend", "green", Point(-96.999, 32.001)),
            candidate("fairway-bend", "fairway", fairway),
        ]
        routes, _ = propose_hole_routes_from_features(
            features, fairway_grid_cell_size_m=5.0
        )
        props = routes[0]["properties"]
        self.assertEqual(props["route_method"], "fairway_grid_path")
        self.assertGreater(
            props["provisional_route_length_m"],
            props["estimated_straight_line_length_m"],
        )
        self.assertGreater(len(routes[0]["geometry"]["coordinates"]), 2)
        self.assertEqual(props["validation_status"], "requires_visual_review")

    def test_requires_tee_and_green_candidates(self):
        with self.assertRaises(ValueError):
            propose_hole_routes_from_features([
                candidate("tee-1", "tee", Point(-97.0, 32.0))
            ])


if __name__ == "__main__":
    unittest.main()
