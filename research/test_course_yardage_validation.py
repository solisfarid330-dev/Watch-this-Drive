import json
import math
import unittest
from pathlib import Path

ROOT = Path(__file__).parent
EARTH_RADIUS_M = 6_371_008.8
METRES_TO_YARDS = 1.0936132983
OFFICIAL_BACK_TEE_YARDS = [
    396, 403, 190, 571, 335, 163, 528, 415, 455,
    407, 409, 133, 547, 435, 165, 428, 541, 410,
]


def haversine_metres(a, b):
    lon1, lat1 = map(math.radians, a)
    lon2, lat2 = map(math.radians, b)
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    h = (
        math.sin(dlat / 2) ** 2
        + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    )
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def line_length_yards(coordinates):
    metres = sum(
        haversine_metres(a, b)
        for a, b in zip(coordinates, coordinates[1:])
    )
    return metres * METRES_TO_YARDS


class CourseYardageValidationTests(unittest.TestCase):
    def test_all_south_padre_hole_lines_are_within_five_percent(self):
        geojson = json.loads(
            (ROOT / "results" / "south-padre" / "features.geojson").read_text()
        )
        holes = [
            feature for feature in geojson["features"]
            if feature.get("properties", {}).get("golf") == "hole"
        ]
        by_ref = {
            int(feature["properties"]["ref"]): feature
            for feature in holes
            if feature["properties"].get("ref")
        }
        self.assertEqual(set(by_ref), set(range(1, 19)))

        differences = []
        for hole_number, official_yards in enumerate(OFFICIAL_BACK_TEE_YARDS, 1):
            feature = by_ref[hole_number]
            self.assertEqual(feature["geometry"]["type"], "LineString")
            measured = line_length_yards(feature["geometry"]["coordinates"])
            difference_pct = abs(measured - official_yards) / official_yards * 100
            differences.append(difference_pct)
            self.assertLess(
                difference_pct,
                5.0,
                f"Hole {hole_number}: {measured:.1f} yd vs {official_yards} yd "
                f"({difference_pct:.2f}%)",
            )

        self.assertEqual(len(differences), 18)
        self.assertLessEqual(max(differences), 4.31)

    def test_saved_yardage_report_matches_recomputed_geometry(self):
        geojson = json.loads(
            (ROOT / "results" / "south-padre" / "features.geojson").read_text()
        )
        report = json.loads(
            (ROOT / "results" / "south-padre" / "yardage-validation.json").read_text()
        )
        holes = {
            int(feature["properties"]["ref"]): feature
            for feature in geojson["features"]
            if feature.get("properties", {}).get("golf") == "hole"
            and feature["properties"].get("ref")
        }
        self.assertEqual(report["summary"]["holes"], 18)
        self.assertEqual(report["summary"]["within_tolerance"], 18)
        self.assertEqual(report["summary"]["scorecard_total_yards"], 6931)
        self.assertEqual(len(report["holes"]), 18)
        for row in report["holes"]:
            measured = line_length_yards(holes[row["hole"]]["geometry"]["coordinates"])
            self.assertAlmostEqual(measured, row["osm_length_yards"], delta=0.1)
            self.assertEqual(row["scorecard_yards"], OFFICIAL_BACK_TEE_YARDS[row["hole"] - 1])


if __name__ == "__main__":
    unittest.main()
