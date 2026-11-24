import json, tempfile, unittest
from pathlib import Path
import numpy as np
from PIL import Image
from fairway_reference_validation import load_mask, score_masks, validate_annotation

class ReferenceMaskTests(unittest.TestCase):
    def test_perfect_prediction(self):
        ref=np.zeros((12,12),bool); ref[3:9,4:8]=True
        result=score_masks(ref,ref.copy(),np.ones_like(ref),ground_sample_distance_m=0.6)
        self.assertEqual(result["iou"],1.0); self.assertEqual(result["dice"],1.0)
        self.assertEqual(result["boundary_f1"],1.0); self.assertEqual(result["area_bias_m2"],0.0)
    def test_uncertain_pixels_excluded(self):
        ref=np.zeros((10,10),bool); ref[2:8,2:8]=True; pred=ref.copy(); pred[4:6,4:6]=False
        uncertain=np.zeros_like(ref); uncertain[4:6,4:6]=True
        result=score_masks(ref,pred,np.ones_like(ref),uncertain,ground_sample_distance_m=1.0)
        self.assertEqual(result["iou"],1.0); self.assertEqual(result["excluded_uncertain_pixels"],4)
    def test_outside_scope_not_scored(self):
        ref=np.zeros((8,8),bool); pred=np.zeros_like(ref); valid=np.zeros_like(ref)
        valid[2:6,2:6]=True; pred[0,0]=True
        self.assertEqual(score_masks(ref,pred,valid,ground_sample_distance_m=1.0)["false_positive_pixels"],0)
    def test_area_accounting(self):
        ref=np.zeros((10,10),bool); pred=np.zeros_like(ref)
        ref[2:6,2:6]=True; pred[2:6,2:5]=True
        result=score_masks(ref,pred,np.ones_like(ref),ground_sample_distance_m=2.0)
        self.assertEqual(result["missed_fairway_area_m2"],16.0); self.assertEqual(result["area_bias_m2"],-16.0)
    def test_mask_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"mask.png"; Image.fromarray(np.array([[0,127],[255,0]],dtype=np.uint8)).save(p)
            with self.assertRaises(ValueError): load_mask(p)
            Image.fromarray(np.zeros((2,2),dtype=np.uint8)).save(p)
            with self.assertRaises(ValueError): load_mask(p,(3,2))
    def test_provenance_and_scope_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); mask=np.zeros((4,5),dtype=np.uint8)
            for name in ("fairway","valid","uncertain"): Image.fromarray(mask).save(root/f"{name}.png")
            data={"schema_version":1,"course":"Example","hole_ref":"1","source_image":"source.png",
              "source_image_sha256":"abc","image_width":5,"image_height":4,"ground_sample_distance_m":0.6,
              "annotation_status":"draft","annotator":"human","created_utc":"2026-01-01T00:00:00Z",
              "masks":{k:f"{k}.png" for k in ("fairway","valid","uncertain")},
              "provenance":{"independent_of_model":True,"used_as_model_input":False}}
            p=root/"annotation.json"; p.write_text(json.dumps(data))
            self.assertEqual(validate_annotation(p)["manifest"]["annotation_status"],"draft")
            data["provenance"]["used_as_model_input"]=True; p.write_text(json.dumps(data))
            with self.assertRaises(ValueError): validate_annotation(p)

if __name__=="__main__": unittest.main()
